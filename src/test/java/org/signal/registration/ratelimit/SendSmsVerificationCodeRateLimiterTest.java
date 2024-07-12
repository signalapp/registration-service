/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import org.junit.jupiter.api.Test;
import org.signal.registration.rpc.MessageTransport;
import org.signal.registration.session.FailedSendAttempt;
import org.signal.registration.session.FailedSendReason;
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
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(0, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addFailedAttempts(FailedSendAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_SUSPECTED_FRAUD)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(0, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addFailedAttempts(FailedSendAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(1, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addFailedAttempts(FailedSendAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_SUSPECTED_FRAUD)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(1, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addFailedAttempts(FailedSendAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_REJECTED)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(1, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(1, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .addFailedAttempts(FailedSendAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_REJECTED)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .addFailedAttempts(FailedSendAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .build()));

    assertEquals(4, rateLimiter.getPriorAttemptCount(RegistrationSession.newBuilder()
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .addFailedAttempts(FailedSendAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_REJECTED)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .addFailedAttempts(FailedSendAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_SUSPECTED_FRAUD)
            .setTimestampEpochMillis(System.currentTimeMillis())
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
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .addFailedAttempts(FailedSendAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .addFailedAttempts(FailedSendAttempt.newBuilder()
            .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_REJECTED)
            .setTimestampEpochMillis(System.currentTimeMillis())
            .build())
        .build()));

    final long firstTimestamp = 37;
    final long secondTimestamp = 41;

    assertEquals(Optional.of(Instant.ofEpochMilli(firstTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestampEpochMillis(firstTimestamp)
                .build())
            .build()));

    assertEquals(Optional.of(Instant.ofEpochMilli(firstTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addFailedAttempts(FailedSendAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_REJECTED)
                .setTimestampEpochMillis(firstTimestamp)
                .build())
            .build()));

    assertEquals(Optional.of(Instant.ofEpochMilli(firstTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestampEpochMillis(firstTimestamp)
                .build())
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                .setTimestampEpochMillis(secondTimestamp)
                .build())
            .addFailedAttempts(FailedSendAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
                .setTimestampEpochMillis(secondTimestamp)
                .build())
            .addFailedAttempts(FailedSendAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_REJECTED)
                .setTimestampEpochMillis(secondTimestamp)
                .build())
            .build()));

    assertEquals(Optional.of(Instant.ofEpochMilli(firstTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addFailedAttempts(FailedSendAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_REJECTED)
                .setTimestampEpochMillis(firstTimestamp)
                .build())
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                .setTimestampEpochMillis(secondTimestamp)
                .build())
            .build()));

    assertEquals(Optional.of(Instant.ofEpochMilli(secondTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestampEpochMillis(firstTimestamp)
                .build())
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestampEpochMillis(secondTimestamp)
                .build())
            .build()));

    assertEquals(Optional.of(Instant.ofEpochMilli(secondTimestamp)),
        rateLimiter.getLastAttemptTime(RegistrationSession.newBuilder()
            .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setTimestampEpochMillis(firstTimestamp)
                .build())
            .addFailedAttempts(FailedSendAttempt.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setFailedSendReason(FailedSendReason.FAILED_SEND_REASON_REJECTED)
                .setTimestampEpochMillis(secondTimestamp)
                .build())
            .build()));
  }

  @Test
  void getTimeOfNextAttemptSmsNotAllowed() {
    final SendSmsVerificationCodeRateLimiter rateLimiter =
        new SendSmsVerificationCodeRateLimiter(List.of(Duration.ZERO), Clock.systemUTC());

    assertEquals(Optional.empty(), rateLimiter.getTimeOfNextAction(RegistrationSession.newBuilder()
        .addRejectedTransports(MessageTransport.MESSAGE_TRANSPORT_SMS)
        .build())
        .join());
  }
}
