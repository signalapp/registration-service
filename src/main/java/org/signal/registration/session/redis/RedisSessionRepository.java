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
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micronaut.retry.annotation.CircuitBreaker;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.session.ConflictingUpdateException;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;

/**
 * A session repository that stores session data in a single (i.e. non-clustered) Redis instance.
 */
@Singleton
@CircuitBreaker(attempts = "${redis-session-repository.circuit-breaker.attempts:3}",
    delay = "${redis-session-repository.circuit-breaker.delay:500ms}",
    reset = "${redis-session-repository.circuit-breaker.reset:5s}")
class RedisSessionRepository implements SessionRepository {

  private final StatefulRedisConnection<byte[], byte[]> redisConnection;

  private final RedisLuaScript updateSessionScript;

  private static final Timer CREATE_SESSION_TIMER = Metrics.timer(MetricsUtil.name(RedisSessionRepository.class, "createSession"));
  private static final Timer GET_SESSION_TIMER = Metrics.timer(MetricsUtil.name(RedisSessionRepository.class, "getSession"));
  private static final Timer UPDATE_SESSION_TIMER = Metrics.timer(MetricsUtil.name(RedisSessionRepository.class, "updateSession"));

  RedisSessionRepository(final StatefulRedisConnection<byte[], byte[]> redisConnection) throws IOException {

    this.redisConnection = redisConnection;

    try (final InputStream scriptInputStream = Objects.requireNonNull(
        getClass().getResourceAsStream("update-session.lua"))) {

      this.updateSessionScript = new RedisLuaScript(scriptInputStream.readAllBytes(), ScriptOutputType.BOOLEAN);
    }
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
        .setSenderCanonicalClassName(sender.getClass().getCanonicalName())
        .setSessionData(ByteString.copyFrom(sessionData))
        .build()
        .toByteArray();

    return redisConnection.async().set(getSessionKey(sessionId), sessionBytes, SetArgs.Builder.ex(ttl))
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
