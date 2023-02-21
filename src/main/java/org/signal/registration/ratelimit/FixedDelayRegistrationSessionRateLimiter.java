/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.signal.registration.session.RegistrationSession;

/**
 * A fixed-delay registration session rate limiter controls the pace and absolute number of times an actor may take an
 * action within the context of a registration session. Both the timing and number of attempts are controlled by a list
 * of durations passed to the rate limiter at construction time.
 */
public abstract class FixedDelayRegistrationSessionRateLimiter implements RateLimiter<RegistrationSession> {

  private final List<Duration> delays;

  private final Clock clock;

  public FixedDelayRegistrationSessionRateLimiter(final List<Duration> delays, final Clock clock) {
    this.delays = delays;
    this.clock = clock;
  }

  protected Clock getClock() {
    return clock;
  }

  /**
   * Returns the number of times the action governed by this rate limiter has already been taken within the given
   * session.
   *
   * @param session the session from which to extract a count of prior attempts
   *
   * @return the number of times the action governed by this rate limiter has already been taken within the given
   * session
   */
  protected abstract int getPriorAttemptCount(final RegistrationSession session);

  /**
   * Returns the most recent time at which the action governed by this rate limiter was taken within the given session.
   *
   * @param session the session from which to extract a time of last action
   *
   * @return the most recent time at which the action governed by this rate limiter was taken within the given session
   * or empty if the action has never been taken; must be non-empty if the prior attempt count is positive
   */
  protected abstract Optional<Instant> getLastAttemptTime(final RegistrationSession session);

  @Override
  public CompletableFuture<Optional<Duration>> getDurationUntilActionAllowed(final RegistrationSession session) {
    final int attempts = getPriorAttemptCount(session);
    final Optional<Instant> maybeLastAttempt = getLastAttemptTime(session);

    final Optional<Duration> maybeDurationUntilActionAllowed;

    if (attempts == 0) {
      // If the caller has never attempted this action before, they may do so immediately
      maybeDurationUntilActionAllowed = Optional.of(Duration.ZERO);
    } else if (attempts <= delays.size()) {
      final Instant nextAllowedAttempt = maybeLastAttempt
          .orElseThrow(() -> new IllegalStateException("Last attempt must be present if attempt count is non-zero"))
          .plus(delays.get(attempts - 1));

      final Instant currentTime = clock.instant();

      if (currentTime.isAfter(nextAllowedAttempt)) {
        // We've crossed the "allowed to take action" threshold, and the caller does not need to wait
        maybeDurationUntilActionAllowed = Optional.of(Duration.ZERO);
      } else {
        maybeDurationUntilActionAllowed = Optional.of(Duration.between(currentTime, nextAllowedAttempt));
      }
    } else {
      // The caller has exhausted all permitted attempts to take the rate-limited action
      maybeDurationUntilActionAllowed = Optional.empty();
    }

    return CompletableFuture.completedFuture(maybeDurationUntilActionAllowed);
  }

  @Override
  public CompletableFuture<Void> checkRateLimit(final RegistrationSession session) {
    return getDurationUntilActionAllowed(session)
        .thenAccept(maybeDurationUntilActionAllowed -> {
          if (maybeDurationUntilActionAllowed.isPresent()) {
            final Duration durationUntilActionAllowed = maybeDurationUntilActionAllowed.get();

            if (!(durationUntilActionAllowed.isZero() || durationUntilActionAllowed.isNegative())) {
              throw new CompletionException(new RateLimitExceededException(durationUntilActionAllowed, session));
            }
          } else {
            throw new CompletionException(new RateLimitExceededException(null, session));
          }
        });
  }
}
