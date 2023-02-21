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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SendSmsVerificationCodeRateLimiterTest {

  @Test
  void getPriorAttemptCount() {
    final SendSmsVerificationCodeRateLimiter rateLimiter =
        new SendSmsVerificationCodeRateLimiter(List.of(Duration.ZERO), Clock.systemUTC());

    assertEquals(0, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder().build()));

    assertEquals(0, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(1, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(1, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(2, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .build()));
  }

  @Test
  void getLastAttemptTime() {
    final SendSmsVerificationCodeRateLimiter rateLimiter =
        new SendSmsVerificationCodeRateLimiter(List.of(Duration.ZERO), Clock.systemUTC());

    assertEquals(Optional.empty(), rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder().build()));

    assertEquals(Optional.empty(), rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setTimestamp(System.currentTimeMillis())
            .build())
        .build()));

    final long firstTimestamp = 37;
    final long secondTimestamp = 41;

    assertEquals(Optional.of(Instant.ofEpochMilli(firstTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestamp(firstTimestamp)
                .build())
            .build()));

    assertEquals(Optional.of(Instant.ofEpochMilli(firstTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestamp(firstTimestamp)
                .build())
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                .setTimestamp(secondTimestamp)
                .build())
            .build()));

    assertEquals(Optional.of(Instant.ofEpochMilli(secondTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestamp(firstTimestamp)
                .build())
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestamp(secondTimestamp)
                .build())
            .build()));
  }
}
