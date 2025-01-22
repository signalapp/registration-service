/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.signal.registration.rpc.MessageTransport;
import org.signal.registration.session.FailedSendReason;
import org.signal.registration.session.RegistrationSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@Singleton
@Named("send-voice-verification-code-per-session")
public class SendVoiceVerificationCodeRateLimiter extends FixedDelayRegistrationSessionRateLimiter {

  private final Duration delayAfterFirstSms;

  public SendVoiceVerificationCodeRateLimiter(
      @Value("${rate-limits.send-voice-verification-code.delay-after-first-sms:1m}") final Duration delayAfterFirstSms,
      @Value("${rate-limits.send-voice-verification-code.delays:1m,5m}") final List<Duration> delays,
      final Clock clock) {

    super(delays, clock);

    this.delayAfterFirstSms = delayAfterFirstSms;
  }

  @Override
  public CompletableFuture<Optional<Instant>> getTimeOfNextAction(final RegistrationSession session) {
    final Optional<Instant> maybeFirstAllowableVoiceCall;

    if (session.getRejectedTransportsList().contains(MessageTransport.MESSAGE_TRANSPORT_SMS)) {
      // The caller has previously tried to send an SMS, but the attempt was rejected by the sender. In these cases,
      // allow the caller to attempt a voice call immediately.
      maybeFirstAllowableVoiceCall = Optional.of(getClock().instant());
    } else {
      // Only allow a voice call attempt if the caller has previously attempted to get a verification code via SMS
      maybeFirstAllowableVoiceCall = session.getRegistrationAttemptsList().stream()
              .filter(attempt -> attempt.getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_SMS)
              .findFirst()
              .map(firstSmsAttempt -> Instant.ofEpochMilli(firstSmsAttempt.getTimestampEpochMillis()).plus(delayAfterFirstSms));
    }

    return CompletableFuture.completedFuture(maybeFirstAllowableVoiceCall.flatMap(firstAllowableVoiceCall -> {
      final Instant currentTime = getClock().instant();

      if (firstAllowableVoiceCall.isAfter(currentTime)) {
        // We're still waiting on the post-first-SMS delay
        return Optional.of(firstAllowableVoiceCall);
      }

      // We've cleared the post-first-SMS delay and should do the normal thing
      return super.getTimeOfNextAction(session).join();
    }));
  }

  @Override
  protected int getPriorAttemptCount(final RegistrationSession session) {
    if (session.getRejectedTransportsList().contains(MessageTransport.MESSAGE_TRANSPORT_VOICE)) {
      // If a sender has affirmatively indicated that it cannot or will not deliver messages via voice call, return a
      // value that guarantees that voice call attempts will appear to have been exhausted
      return Integer.MAX_VALUE;
    }

    return (int) session.getRegistrationAttemptsList().stream()
        .filter(attempt -> attempt.getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_VOICE)
        .count() +
            (int) session.getFailedAttemptsList().stream()
                    .filter(attempt -> attempt.getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_VOICE)
                    .filter(attempt -> attempt.getFailedSendReason() != FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
                    .count();
  }

  @Override
  protected Optional<Instant> getLastAttemptTime(final RegistrationSession session) {
    return Stream.concat(
            session.getRegistrationAttemptsList().stream()
                    .filter(attempt -> attempt.getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_VOICE)
                    .map(attempt -> Instant.ofEpochMilli(attempt.getTimestampEpochMillis())),
            session.getFailedAttemptsList().stream()
                    .filter(attempt -> attempt.getMessageTransport() == MessageTransport.MESSAGE_TRANSPORT_VOICE)
                    .filter(attempt -> attempt.getFailedSendReason() != FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
                    .map(attempt -> Instant.ofEpochMilli(attempt.getTimestampEpochMillis())))
        .max(Comparator.naturalOrder());
  }
}
