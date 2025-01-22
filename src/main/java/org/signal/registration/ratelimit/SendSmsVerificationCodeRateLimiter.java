/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.signal.registration.rpc.MessageTransport;
import org.signal.registration.session.FailedSendReason;
import org.signal.registration.session.RegistrationSession;

@Singleton
@Named("send-sms-verification-code-per-session")
public class SendSmsVerificationCodeRateLimiter extends FixedDelayRegistrationSessionRateLimiter {

  public SendSmsVerificationCodeRateLimiter(
      @Value("${rate-limits.send-sms-verification-code.delays:1m,2m,5m,10m}") final List<Duration> delays,
      final Clock clock) {

    super(delays, clock);
  }

  @Override
  protected int getPriorAttemptCount(final RegistrationSession session) {
    if (session.getRejectedTransportsList().contains(MessageTransport.MESSAGE_TRANSPORT_SMS)) {
      // If a sender has affirmatively indicated that it cannot or will not deliver messages via SMS, return a value
      // that guarantees that SMS attempts will appear to have been exhausted
      return Integer.MAX_VALUE;
    }

    return (int) session.getRegistrationAttemptsList().stream()
        .filter(attempt -> attempt.getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_SMS)
        .count() +
            (int) session.getFailedAttemptsList().stream()
                    .filter(attempt -> attempt.getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .filter(attempt -> attempt.getFailedSendReason() != FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
                    .count();
  }

  @Override
  protected Optional<Instant> getLastAttemptTime(final RegistrationSession session) {
    return Stream.concat(
            session.getRegistrationAttemptsList().stream()
                    .filter(attempt -> attempt.getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .map(attempt -> Instant.ofEpochMilli(attempt.getTimestampEpochMillis())),
            session.getFailedAttemptsList().stream()
                    .filter(attempt -> attempt.getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_SMS)
                    .filter(attempt -> attempt.getFailedSendReason() != FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
                    .map(attempt -> Instant.ofEpochMilli(attempt.getTimestampEpochMillis()))
            )
            .max(Comparator.naturalOrder());
  }
}
