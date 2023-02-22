/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.util.CompletionExceptions;

class FixedDelayRegistrationSessionRateLimiterTest {

  private static class TestRateLimiter extends FixedDelayRegistrationSessionRateLimiter {

    private final int priorAttemptCount;

    @Nullable
    private final Instant lastAttempt;

    private TestRateLimiter(final int priorAttemptCount,
        @Nullable final Instant lastAttempt,
        final List<Duration> delays,
        final Clock clock) {

      super(delays, clock);

      this.priorAttemptCount = priorAttemptCount;
      this.lastAttempt = lastAttempt;
    }

    @Override
    protected int getPriorAttemptCount(final RegistrationSession session) {
      return priorAttemptCount;
    }

    @Override
    protected Optional<Instant> getLastAttemptTime(final RegistrationSession session) {
      return Optional.ofNullable(lastAttempt);
    }
  }

  @BeforeEach
  void setUp() {
  }

  @ParameterizedTest
  @MethodSource("getRateLimitTestArguments")
  void getTimeOfNextAction(final int priorAttemptCount,
      @Nullable final Instant lastAttempt,
      final Instant currentTime,
      final List<Duration> delays,
      @Nullable final Instant expectedTimeOfNextAction) {

    final FixedDelayRegistrationSessionRateLimiter rateLimiter =
        new TestRateLimiter(priorAttemptCount, lastAttempt, delays, Clock.fixed(currentTime, ZoneId.systemDefault()));

    final Optional<Instant> maybeTimeOfNextAction =
        rateLimiter.getTimeOfNextAction(RegistrationSession.newBuilder().build()).join();

    if (expectedTimeOfNextAction != null && !expectedTimeOfNextAction.isAfter(currentTime)) {
      // Accept any "allowed now" timestamp (anything less than or equal to the current time)
      assertTrue(maybeTimeOfNextAction.map(timeOfNextAction ->
              timeOfNextAction.equals(expectedTimeOfNextAction) || timeOfNextAction.isBefore(expectedTimeOfNextAction))
          .orElse(false));
    } else {
      // Expect an exact match (either no value or a specific time in the future)
      assertEquals(Optional.ofNullable(expectedTimeOfNextAction), maybeTimeOfNextAction);
    }
  }

  @ParameterizedTest
  @MethodSource("getRateLimitTestArguments")
  void checkRateLimit(final int priorAttemptCount,
      @Nullable final Instant lastAttempt,
      final Instant currentTime,
      final List<Duration> delays,
      @Nullable final Instant expectedTimeOfNextAction) {

    final FixedDelayRegistrationSessionRateLimiter rateLimiter =
        new TestRateLimiter(priorAttemptCount, lastAttempt, delays, Clock.fixed(currentTime, ZoneId.systemDefault()));

    if (currentTime.equals(expectedTimeOfNextAction)) {
      assertDoesNotThrow(() -> rateLimiter.checkRateLimit(RegistrationSession.newBuilder().build()).join());
    } else {
      final CompletionException completionException = assertThrows(CompletionException.class,
          () -> rateLimiter.checkRateLimit(RegistrationSession.newBuilder().build()).join());

      if (CompletionExceptions.unwrap(completionException) instanceof final RateLimitExceededException rateLimitExceededException) {

        assertEquals(
            Optional.ofNullable(expectedTimeOfNextAction).map(nextAction -> Duration.between(currentTime, nextAction)),
            rateLimitExceededException.getRetryAfterDuration());
      } else {
        fail("Expected RateLimitExceededException");
      }
    }
  }

  private static Stream<Arguments> getRateLimitTestArguments() {
    final List<Duration> delays = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2));
    final Instant currentTime = Instant.now();

    return Stream.of(
        // No prior attempts; action should be allowed immediately
        Arguments.of(0, null, currentTime, delays, currentTime),

        // One prior attempt; action should be allowed after first delay
        Arguments.of(1, currentTime, currentTime, delays, currentTime.plus(Duration.ofMinutes(1))),

        // Two prior attempts; action should be allowed after second delay
        Arguments.of(2, currentTime, currentTime, delays, currentTime.plus(Duration.ofMinutes(2))),

        // Three prior attempts; action should no longer be allowed
        Arguments.of(3, currentTime, currentTime, delays, null),

        // One prior attempt with partially-elapsed delay; action should be allowed after partial delay
        Arguments.of(1, currentTime.minusSeconds(30), currentTime, delays, currentTime.plus(Duration.ofSeconds(30))),

        // One prior attempt with fully-elapsed delay; action should be allowed immediately
        Arguments.of(1, currentTime.minusSeconds(120), currentTime, delays, currentTime)
    );
  }
}
