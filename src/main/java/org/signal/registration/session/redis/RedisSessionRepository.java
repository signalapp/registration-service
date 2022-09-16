/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.lettuce.core.ScanStream;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.Value;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.retry.annotation.CircuitBreaker;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.session.ConflictingUpdateException;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A session repository that stores session data in a single (i.e. non-clustered) Redis instance.
 * <p/>
 * This repository stores one session per key (see {@link #getSessionKey(UUID)}). When creating a session, it adds the
 * session's key to an expiration queue (a <a href="https://redis.io/docs/data-types/sorted-sets/">sorted set</a> that
 * uses expiration timestamps as "scores") and also sets a fallback TTL. The repository will periodically check for
 * entries in the expiration queue that have expiration times in the past and will clear those sessions from the backing
 * store, triggering a {@link org.signal.registration.session.SessionCompletedEvent} for each session removed.
 * <p/>
 * As a safety mechanism, Redis itself will use the fallback TTL to clear stale keys if the repository fails to do so
 * for any reason, though sessions removed in that manner will not trigger a {@code SessionCompletedEvent}.
 */
@Singleton
@CircuitBreaker(attempts = "${redis-session-repository.circuit-breaker.attempts:3}",
    delay = "${redis-session-repository.circuit-breaker.delay:500ms}",
    reset = "${redis-session-repository.circuit-breaker.reset:5s}")
class RedisSessionRepository implements SessionRepository {

  private final StatefulRedisConnection<byte[], byte[]> redisConnection;
  private final ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher;
  private final Clock clock;

  private final RedisLuaScript updateSessionScript;

  @VisibleForTesting
  static final byte[] EXPIRATION_QUEUE_KEY = "expiration-queue".getBytes(StandardCharsets.UTF_8);
  private static final Duration FALLBACK_TTL_PADDING = Duration.ofMinutes(2);

  private static final Timer CREATE_SESSION_TIMER = Metrics.timer(MetricsUtil.name(RedisSessionRepository.class, "createSession"));
  private static final Timer GET_SESSION_TIMER = Metrics.timer(MetricsUtil.name(RedisSessionRepository.class, "getSession"));
  private static final Timer UPDATE_SESSION_TIMER = Metrics.timer(MetricsUtil.name(RedisSessionRepository.class, "updateSession"));

  private static final Logger logger = LoggerFactory.getLogger(RedisSessionRepository.class);

  RedisSessionRepository(final StatefulRedisConnection<byte[], byte[]> redisConnection,
      final ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher,
      final Clock clock) throws IOException {

    this.redisConnection = redisConnection;
    this.sessionCompletedEventPublisher = sessionCompletedEventPublisher;
    this.clock = clock;

    try (final InputStream scriptInputStream = Objects.requireNonNull(
        getClass().getResourceAsStream("update-session.lua"))) {

      this.updateSessionScript = new RedisLuaScript(scriptInputStream.readAllBytes(), ScriptOutputType.BOOLEAN);
    }
  }

  @Scheduled(fixedDelay = "${redis-session-repository.remove-expired-sessions-interval:10s}")
  @VisibleForTesting
  long removeExpiredSessions() {
    return ScanStream.zscan(redisConnection.reactive(), EXPIRATION_QUEUE_KEY)
        .filter(scoredValue -> scoredValue.getScore() < clock.millis())
        .map(Value::getValue)
        .flatMap(sessionKey -> redisConnection.reactive().zrem(EXPIRATION_QUEUE_KEY, sessionKey)
            .mapNotNull(removed -> removed > 0 ? sessionKey : null))
        .flatMap(sessionKey -> redisConnection.reactive().getdel(sessionKey))
        .filter(Objects::nonNull)
        .mapNotNull(sessionData -> {
          try {
            return RegistrationSession.parseFrom(sessionData);
          } catch (final InvalidProtocolBufferException e) {
            logger.warn("Failed to parse expired session", e);
            return null;
          }
        })
        .doOnNext(session -> sessionCompletedEventPublisher.publishEventAsync(new SessionCompletedEvent(session)))
        .count()
        .blockOptional()
        .orElse(0L);
  }

  @Override
  public CompletableFuture<UUID> createSession(final Phonenumber.PhoneNumber phoneNumber,
      final VerificationCodeSender sender,
      final Duration ttl,
      final byte[] sessionData) {

    final Timer.Sample sample = Timer.start();

    final UUID sessionId = UUID.randomUUID();
    final byte[] sessionBytes = RegistrationSession.newBuilder()
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164))
        .setSenderName(sender.getName())
        .setSessionData(ByteString.copyFrom(sessionData))
        .build()
        .toByteArray();

    final Instant expiration = clock.instant().plus(ttl);

    return redisConnection.async().set(getSessionKey(sessionId), sessionBytes, SetArgs.Builder.px(ttl.plus(FALLBACK_TTL_PADDING)))
        .thenCompose(ignored -> redisConnection.async().zadd(EXPIRATION_QUEUE_KEY, expiration.toEpochMilli(), getSessionKey(sessionId)))
        .thenApply(ignored -> sessionId)
        .whenComplete((id, throwable) -> sample.stop(CREATE_SESSION_TIMER))
        .toCompletableFuture();
  }

  @Override
  public CompletableFuture<RegistrationSession> getSession(final UUID sessionId) {
    final Timer.Sample sample = Timer.start();

    return redisConnection.async().get(getSessionKey(sessionId))
        .thenApply(sessionBytes -> {
          if (sessionBytes != null) {
            try {
              return RegistrationSession.parseFrom(sessionBytes);
            } catch (final InvalidProtocolBufferException e) {
              throw new UncheckedIOException(e);
            }
          } else {
            throw new CompletionException(new SessionNotFoundException());
          }
        })
        .whenComplete((session, throwable) -> sample.stop(GET_SESSION_TIMER))
        .toCompletableFuture();
  }

  @Override
  public CompletableFuture<RegistrationSession> updateSession(final UUID sessionId,
      final Function<RegistrationSession, RegistrationSession> sessionUpdater) {

    final Timer.Sample sample = Timer.start();

    return getSession(sessionId)
        .thenCompose(existingSession -> {
          final RegistrationSession updatedSession = sessionUpdater.apply(existingSession);

          final CompletableFuture<Boolean> updateFuture =
              updateSessionScript.execute(redisConnection, new byte[][] { getSessionKey(sessionId )},
                  existingSession.toByteArray(), updatedSession.toByteArray());

          return updateFuture.thenApply(updateSuccessful -> {
            if (updateSuccessful) {
              return updatedSession;
            } else {
              throw new CompletionException(new ConflictingUpdateException());
            }
          });
        })
        .whenComplete((session, throwable) -> sample.stop(UPDATE_SESSION_TIMER));
  }

  @VisibleForTesting
  static byte[] getSessionKey(final UUID uuid) {
    return ("registration-session::" + uuid).getBytes(StandardCharsets.UTF_8);
  }
}
