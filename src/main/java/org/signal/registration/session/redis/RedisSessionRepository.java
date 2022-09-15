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
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micronaut.retry.annotation.CircuitBreaker;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.VerificationCodeSender;
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

  private final Timer createSessionTimer;
  private final Timer getSessionTimer;
  private final Timer setSessionVerifiedTimer;

  @VisibleForTesting
  static final String KEY_SENDER_CLASS = "sender";

  RedisSessionRepository(final StatefulRedisConnection<byte[], byte[]> redisConnection) throws IOException {

    this.redisConnection = redisConnection;

    createSessionTimer = Metrics.timer(MetricsUtil.name(getClass(), "createSession"));
    getSessionTimer = Metrics.timer(MetricsUtil.name(getClass(), "getSession"));
    setSessionVerifiedTimer = Metrics.timer(MetricsUtil.name(getClass(), "setSessionVerified"));
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
        .whenComplete((id, throwable) -> sample.stop(createSessionTimer))
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
        .whenComplete((session, throwable) -> sample.stop(getSessionTimer))
        .toCompletableFuture();
  }

  @Override
  public CompletableFuture<Void> setSessionVerified(final UUID sessionId, final String verificationCode) {
    final Timer.Sample sample = Timer.start();

    return getSession(sessionId)
        .thenCompose(session -> redisConnection.async().set(getSessionKey(sessionId),
            session.toBuilder().setVerifiedCode(verificationCode).build().toByteArray(),
            SetArgs.Builder.keepttl()))
        .thenAccept(ignored -> {})
        .whenComplete((ignored, throwable) -> sample.stop(setSessionVerifiedTimer));
  }

  @VisibleForTesting
  static byte[] getSessionKey(final UUID uuid) {
    return ("registration-session::" + uuid).getBytes(StandardCharsets.UTF_8);
  }
}
