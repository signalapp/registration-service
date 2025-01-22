/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.signal.registration.session.RegistrationSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Singleton
@Named("check-verification-code-per-session")
public class CheckVerificationCodeRateLimiter extends FixedDelayRegistrationSessionRateLimiter {

  public CheckVerificationCodeRateLimiter(
      @Value("${rate-limits.check-verification-code.delays:0s,0s,1m,2m}") final List<Duration> delays,
      final Clock clock) {

    super(delays, clock);
  }

  @Override
  protected int getPriorAttemptCount(final RegistrationSession session) {
    return session.getCheckCodeAttempts();
  }

  @Override
  protected Optional<Instant> getLastAttemptTime(final RegistrationSession session) {
    return getPriorAttemptCount(session) > 0
        ? Optional.of(Instant.ofEpochMilli(session.getLastCheckCodeAttemptEpochMillis()))
        : Optional.empty();
  }
}
