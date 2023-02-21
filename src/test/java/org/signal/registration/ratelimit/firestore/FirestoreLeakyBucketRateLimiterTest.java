/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit.firestore;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.util.CompletionExceptions;
import org.signal.registration.util.FirestoreTestUtil;
import org.signal.registration.util.FirestoreUtil;
import org.testcontainers.containers.FirestoreEmulatorContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FirestoreLeakyBucketRateLimiterTest {

  private Firestore firestore;
  private ExecutorService executor;

  private static final String PROJECT_ID = "firestore-rate-limiter-test";
  private static final String FIRESTORE_EMULATOR_IMAGE_NAME = "gcr.io/google.com/cloudsdktool/cloud-sdk:" +
      System.getProperty("firestore.emulator.version", "emulators");

  @Container
  private static final FirestoreEmulatorContainer CONTAINER = new FirestoreEmulatorContainer(
      DockerImageName.parse(FIRESTORE_EMULATOR_IMAGE_NAME));

  private static class TestLeakyBucketRateLimiter extends FirestoreLeakyBucketRateLimiter<String> {


    public TestLeakyBucketRateLimiter(final Firestore firestore,
                                      final Executor executor,
                                      final Clock clock,
                                      final int maxCapacity,
                                      final Duration permitRegenerationPeriod,
                                      final Duration minDelay) {

      super(firestore, executor, clock, "rate-limit", "expiration", maxCapacity,
          permitRegenerationPeriod, minDelay);
    }

    @Override
    protected String getDocumentId(final String key) {
      return key;
    }
  }

  @BeforeEach
  void setUp() throws IOException {
    FirestoreTestUtil.clearFirestoreDatabase(CONTAINER, PROJECT_ID);
    firestore = FirestoreTestUtil.buildFirestoreClient(CONTAINER, PROJECT_ID);

    executor = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void tearDown() throws Exception {
    firestore.close();

    executor.shutdown();

    //noinspection ResultOfMethodCallIgnored
    executor.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  void checkRateLimit() {
    final Duration permitRegenerationPeriod = Duration.ofSeconds(47);
    final Instant currentTime = Instant.now().truncatedTo(ChronoUnit.MICROS);

    final FirestoreLeakyBucketRateLimiter<String> rateLimiter =
        new TestLeakyBucketRateLimiter(firestore, executor, Clock.fixed(currentTime, ZoneId.systemDefault()),
            2, permitRegenerationPeriod, Duration.ZERO);

    final String key = "key";

    assertDoesNotThrow(() -> rateLimiter.checkRateLimit(key).join(),
        "Rate limiter should return normally when bucket doesn't initially exist");

    assertDoesNotThrow(() -> rateLimiter.checkRateLimit(key).join(),
        "Rate limiter should return normally when bucket exists, but has permits");

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> rateLimiter.checkRateLimit(key).join(),
        "Rate limiter should return exceptionally when bucket exists and has no permits");

    assertTrue(CompletionExceptions.unwrap(completionException) instanceof RateLimitExceededException);
    assertEquals(Optional.of(permitRegenerationPeriod),
        ((RateLimitExceededException) CompletionExceptions.unwrap(completionException)).getRetryAfterDuration());
  }

  @Test
  void checkRateLimitCooldown() {
    final Instant currentTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
    final Duration minDelay = Duration.ofSeconds(47);

    final FirestoreLeakyBucketRateLimiter<String> rateLimiter =
        new TestLeakyBucketRateLimiter(firestore, executor, Clock.fixed(currentTime, ZoneId.systemDefault()),
            10, Duration.ofMinutes(1), minDelay);

    final String key = "key";

    assertDoesNotThrow(() -> rateLimiter.checkRateLimit(key).join(),
        "Rate limiter should return normally when bucket doesn't initially exist");

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> rateLimiter.checkRateLimit(key).join(),
        "Rate limiter should return exceptionally when bucket exists and has permits, but cooldown period is in effect");

    assertTrue(CompletionExceptions.unwrap(completionException) instanceof RateLimitExceededException);
    assertEquals(Optional.of(minDelay),
        ((RateLimitExceededException) CompletionExceptions.unwrap(completionException)).getRetryAfterDuration());
  }

  @Test
  void takePermit() {
    final Duration permitRegenerationPeriod = Duration.ofSeconds(47);
    final Instant currentTime = Instant.now().truncatedTo(ChronoUnit.MICROS);

    final FirestoreLeakyBucketRateLimiter<String> rateLimiter =
        new TestLeakyBucketRateLimiter(firestore, executor, Clock.fixed(currentTime, ZoneId.systemDefault()),
            2, permitRegenerationPeriod, Duration.ZERO);

    final String key = "key";

    assertEquals(Duration.ZERO, rateLimiter.takePermit(key).join(),
        "Rate limiter should not return a time to next permit when bucket doesn't exist");

    assertEquals(Duration.ZERO, rateLimiter.takePermit(key).join(),
        "Rate limiter should not return a time to next permit when bucket exists, but has permits");

    assertEquals(permitRegenerationPeriod, rateLimiter.takePermit(key).join(),
        "Rate limiter should return the correct time to next permit when bucket is empty");
  }

  @Test
  void takePermitCooldown() {
    final Duration minDelay = Duration.ofSeconds(47);
    final Instant currentTime = Instant.now().truncatedTo(ChronoUnit.MICROS);

    final FirestoreLeakyBucketRateLimiter<String> rateLimiter =
        new TestLeakyBucketRateLimiter(firestore, executor, Clock.fixed(currentTime, ZoneId.systemDefault()),
            2, Duration.ofMinutes(1), minDelay);

    final String key = "key";

    assertEquals(Duration.ZERO, rateLimiter.takePermit(key).join(),
        "Rate limiter should not return a time to next permit when bucket doesn't exist");

    assertEquals(minDelay, rateLimiter.takePermit(key).join(),
        "Rate limiter should return the correct time to next permit when bucket has permits, but cooldown is in effect");
  }

  @ParameterizedTest
  @MethodSource
  void getCurrentPermitsAvailable(final double initialPermitsAvailable,
      final Instant lastPermitGranted,
      final Instant currentTime,
      final int maxCapacity,
      final Duration permitRegenerationPeriod,
      final double expectedPermitsAvailable) {

    final FirestoreLeakyBucketRateLimiter<String> rateLimiter =
        new TestLeakyBucketRateLimiter(mock(Firestore.class),
            mock(Executor.class),
            Clock.fixed(currentTime, ZoneId.systemDefault()),
            maxCapacity,
            permitRegenerationPeriod,
            Duration.ZERO);

    assertEquals(expectedPermitsAvailable, rateLimiter.getCurrentPermitsAvailable(initialPermitsAvailable, lastPermitGranted));
  }

  private static Stream<Arguments> getCurrentPermitsAvailable() {
    final Instant currentTime = Instant.now().truncatedTo(ChronoUnit.MICROS);

    return Stream.of(
        // No elapsed time since last permit granted
        Arguments.of(1, currentTime, currentTime, 10, Duration.ofMinutes(1), 1),

        // Single regeneration period elapsed
        Arguments.of(1, currentTime, currentTime.plus(Duration.ofMinutes(1)), 10, Duration.ofMinutes(1), 2),

        // Fractional period elapsed
        Arguments.of(1, currentTime, currentTime.plus(Duration.ofSeconds(30)), 10, Duration.ofMinutes(1), 1.5),

        // Many periods elapsed, but regeneration limited by max capacity
        Arguments.of(1, currentTime, currentTime.plus(Duration.ofMinutes(30)), 10, Duration.ofMinutes(1), 10),

        // Negative time elapsed
        Arguments.of(1, currentTime, currentTime.minus(Duration.ofMinutes(1)), 10, Duration.ofMinutes(1), 0)
    );
  }

  @ParameterizedTest
  @MethodSource
  void getTimeUntilPermitsAvailable(final double permitsAvailable,
      final int desiredPermits,
      final Instant currentTime,
      final Duration permitRegenerationPeriod,
      final Instant expectedTimeUntilPermitAvailable) {

    final FirestoreLeakyBucketRateLimiter<String> rateLimiter = new TestLeakyBucketRateLimiter(mock(Firestore.class),
        mock(Executor.class),
        Clock.fixed(currentTime, ZoneId.systemDefault()),
        Integer.MAX_VALUE,
        permitRegenerationPeriod,
        Duration.ZERO);

    assertEquals(expectedTimeUntilPermitAvailable, rateLimiter.getPermitAvailabilityTime(permitsAvailable, desiredPermits));
  }

  private static Stream<Arguments> getTimeUntilPermitsAvailable() {
    final Instant currentTime = Instant.now().truncatedTo(ChronoUnit.MICROS);

    return Stream.of(
        // No permits available
        Arguments.of(0, 1, currentTime, Duration.ofMinutes(1), currentTime.plusSeconds(60)),

        // A half permit is available
        Arguments.of(0.5, 1, currentTime, Duration.ofMinutes(1), currentTime.plusSeconds(30)),

        // The desired number of permits are already available
        Arguments.of(1, 1, currentTime, Duration.ofMinutes(1), currentTime),

        // More permits are available than we need
        Arguments.of(5, 1, currentTime, Duration.ofMinutes(1), currentTime.minusSeconds(240))
    );
  }

  @ParameterizedTest
  @MethodSource
  void getBucketExpirationTimestamp(final double permitsAvailable,
      final int maxPermits,
      final Duration minDelay,
      final Instant lastPermitGranted,
      final Instant currentTime,
      final Duration permitRegenerationPeriod,
      final Timestamp expectedExpirationTimestamp) {

    final FirestoreLeakyBucketRateLimiter<String> rateLimiter = new TestLeakyBucketRateLimiter(mock(Firestore.class),
        mock(Executor.class),
        Clock.fixed(currentTime, ZoneId.systemDefault()),
        maxPermits,
        permitRegenerationPeriod,
        minDelay);

    assertEquals(expectedExpirationTimestamp, rateLimiter.getBucketExpirationTimestamp(permitsAvailable, lastPermitGranted));
  }

  private static Stream<Arguments> getBucketExpirationTimestamp() {
    final Instant currentTime = Instant.now().truncatedTo(ChronoUnit.MICROS);

    return Stream.of(
        // Bucket is empty and min delay is zero
        Arguments.of(0, 1, Duration.ZERO, currentTime, currentTime, Duration.ofMinutes(1),
            FirestoreUtil.timestampFromInstant(currentTime.plusSeconds(60))),

        // Bucket is partially full and min delay is zero
        Arguments.of(0.5, 1, Duration.ZERO, currentTime, currentTime, Duration.ofMinutes(1),
            FirestoreUtil.timestampFromInstant(currentTime.plusSeconds(30))),

        // Bucket is full and min delay is zero
        Arguments.of(1, 1, Duration.ZERO, currentTime, currentTime, Duration.ofMinutes(1),
            FirestoreUtil.timestampFromInstant(currentTime)),

        // Bucket is full and min delay is non-zero
        Arguments.of(1, 1, Duration.ofSeconds(79), currentTime, currentTime, Duration.ofMinutes(1),
            FirestoreUtil.timestampFromInstant(currentTime.plusSeconds(79)))
    );
  }

  @Test
  void latestOf() {
    final Instant now = Instant.now();
    final Instant later = Instant.now().plusMillis(1);

    assertEquals(later, FirestoreLeakyBucketRateLimiter.latestOf(now, later));
    assertEquals(later, FirestoreLeakyBucketRateLimiter.latestOf(later, now));
  }
}
