/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import org.junit.jupiter.api.Test;
import org.signal.registration.session.MessageTransport;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SendVoiceVerificationCodeRateLimiterTest {

  @Test
  void getDurationUntilActionAllowed() {
    final Instant currentTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    final Duration delayAfterFirstSms = Duration.ofMinutes(13);
    final List<Duration> delays = List.of(Duration.ofMinutes(3), Duration.ofMinutes(5));

    final SendVoiceVerificationCodeRateLimiter rateLimiter =
        new SendVoiceVerificationCodeRateLimiter(delayAfterFirstSms, delays, Clock.fixed(currentTime, ZoneId.systemDefault()));

    // No prior SMS
    assertEquals(Optional.empty(),
        rateLimiter.getDurationUntilActionAllowed(RegistrationSession.newBuilder().build()).join());

    // Still in "cooldown" period from first SMS
    assertEquals(Optional.of(delayAfterFirstSms),
        rateLimiter.getDurationUntilActionAllowed(RegistrationSession.newBuilder()
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .setTimestamp(currentTime.toEpochMilli())
                    .build())
            .build())
            .join());

    // After SMS "cooldown" period, but before first voice verification code
    assertEquals(Optional.of(Duration.ZERO),
        rateLimiter.getDurationUntilActionAllowed(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestamp(currentTime.minus(delayAfterFirstSms).toEpochMilli())
                .build())
            .build())
            .join());

    // After first voice verification code
    assertEquals(Optional.of(delays.get(0)),
        rateLimiter.getDurationUntilActionAllowed(RegistrationSession.newBuilder()
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .setTimestamp(currentTime.minus(delayAfterFirstSms).toEpochMilli())
                    .build())
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                    .setTimestamp(currentTime.toEpochMilli())
                    .build())
                .build())
            .join());

    // Voice verification attempts exhausted
    assertEquals(Optional.empty(),
        rateLimiter.getDurationUntilActionAllowed(RegistrationSession.newBuilder()
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .setTimestamp(currentTime.minus(delayAfterFirstSms).minus(delayAfterFirstSms).toEpochMilli())
                    .build())
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                    .setTimestamp(currentTime.minus(delays.get(0)).minus(delays.get(1)).toEpochMilli())
                    .build())
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                    .setTimestamp(currentTime.minus(delays.get(1)).toEpochMilli())
                    .build())
                .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                    .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                    .setTimestamp(currentTime.toEpochMilli())
                    .build())
                .build())
            .join());
  }

  @Test
  void getPriorAttemptCount() {
    final SendVoiceVerificationCodeRateLimiter rateLimiter =
        new SendVoiceVerificationCodeRateLimiter(Duration.ZERO, List.of(Duration.ZERO), Clock.systemUTC());

    assertEquals(0, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder().build()));

    assertEquals(0, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(1, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(1, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(2, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .build()));
  }

  @Test
  void getLastAttemptTime() {
    final SendVoiceVerificationCodeRateLimiter rateLimiter =
        new SendVoiceVerificationCodeRateLimiter(Duration.ZERO, List.of(Duration.ZERO), Clock.systemUTC());

    assertEquals(Optional.empty(), rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder().build()));

    assertEquals(Optional.empty(), rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .build()));

    final long firstTimestamp = 37;
    final long secondTimestamp = 41;

    assertEquals(Optional.of(Instant.ofEpochMilli(firstTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                .setTimestamp(firstTimestamp)
                .build())
            .build()));

    assertEquals(Optional.of(Instant.ofEpochMilli(firstTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                .setTimestamp(firstTimestamp)
                .build())
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestamp(secondTimestamp)
                .build())
            .build()));

    assertEquals(Optional.of(Instant.ofEpochMilli(secondTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                .setTimestamp(firstTimestamp)
                .build())
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                .setTimestamp(secondTimestamp)
                .build())
            .build()));
  }
}
