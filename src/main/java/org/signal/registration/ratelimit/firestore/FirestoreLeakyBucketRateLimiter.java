/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit.firestore;

import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.common.annotations.VisibleForTesting;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.util.FirestoreUtil;

/**
 * A Firestore token bucket rate limiter controls the rate at which actions may be taken using a
 * <a href="https://en.wikipedia.org/wiki/Leaky_bucket">leaky bucket</a> algorithm. Each key in a
 * {@code FirestoreTokenBucketRateLimiter} maps to a distinct "bucket."
 *
 * @param <K> the type of key that identifies a token bucket for this rate limiter
 */
public abstract class FirestoreLeakyBucketRateLimiter<K> implements RateLimiter<K> {

  private final Firestore firestore;
  private final Executor executor;
  private final FirestoreLeakyBucketRateLimiterConfiguration configuration;
  private final Clock clock;

  private static final String AVAILABLE_PERMITS_FIELD_NAME = "available-permits";
  private static final String LAST_PERMIT_GRANTED_FIELD_NAME = "last-permit-granted";

  public FirestoreLeakyBucketRateLimiter(final Firestore firestore,
                                         @Named(TaskExecutors.IO) final Executor executor,
                                         final FirestoreLeakyBucketRateLimiterConfiguration configuration,
                                         final Clock clock) {

    this.firestore = firestore;
    this.executor = executor;
    this.configuration = configuration;
    this.clock = clock;
  }

  /**
   * Convert a key to a Firestore document ID.
   *
   * @param key the key to convert to a Firestore document ID
   *
   * @return a string that uniquely identifies a Firestore document
   */
  protected abstract String getDocumentId(final K key);

  @Override
  public CompletableFuture<Void> checkRateLimit(final K key) {
    return takePermit(key)
        .thenAccept(maybeNextPermitAvailableTime -> maybeNextPermitAvailableTime.ifPresent(nextPermitAvailableTime -> {
          throw new CompletionException(new RateLimitExceededException(Duration.between(clock.instant(), nextPermitAvailableTime)));
        }));
  }

  @VisibleForTesting
  CompletableFuture<Optional<Instant>> takePermit(final K key) {
    return FirestoreUtil.toCompletableFuture(firestore.runAsyncTransaction(transaction -> {
      final DocumentReference documentReference =
          firestore.collection(configuration.collectionName()).document(getDocumentId(key));

      return ApiFutures.transform(transaction.get(documentReference), documentSnapshot -> {
        final Instant currentTime = clock.instant();
        final Optional<Instant> maybeNextPermitAvailableTime;

        if (documentSnapshot.exists()) {
          final Instant lastPermitGranted;
          {
            final Timestamp lastPermitGrantedTimestamp = documentSnapshot.getTimestamp(LAST_PERMIT_GRANTED_FIELD_NAME);

            lastPermitGranted = lastPermitGrantedTimestamp != null ?
                FirestoreUtil.instantFromTimestamp(lastPermitGrantedTimestamp) : currentTime;
          }

          final double currentAvailablePermits;
          {
            final Double permitsAvailableFromDocument = documentSnapshot.getDouble(AVAILABLE_PERMITS_FIELD_NAME);

            currentAvailablePermits = permitsAvailableFromDocument != null ?
                getCurrentPermitsAvailable(permitsAvailableFromDocument, lastPermitGranted) :
                configuration.maxCapacity();
          }

          final Instant nextTimeToTakePermit = lastPermitGranted.plus(configuration.minDelay());
          final Instant permitAvailableTime = getPermitAvailabilityTime(currentAvailablePermits, 1);

          // We could be waiting on one, both, or neither of permit availability or the between-action cooldown.
          final Instant nextActionAllowed = latestOf(nextTimeToTakePermit, permitAvailableTime);

          if (nextActionAllowed.isAfter(currentTime)) {
            maybeNextPermitAvailableTime = Optional.of(nextActionAllowed);
          } else {
            transaction.update(documentReference, Map.of(
                AVAILABLE_PERMITS_FIELD_NAME, currentAvailablePermits - 1,
                LAST_PERMIT_GRANTED_FIELD_NAME, FirestoreUtil.timestampFromInstant(currentTime),
                configuration.expirationFieldName(),
                getBucketExpirationTimestamp(currentAvailablePermits - 1, lastPermitGranted)));

            maybeNextPermitAvailableTime = Optional.empty();
          }
        } else {
          transaction.create(documentReference, Map.of(
              AVAILABLE_PERMITS_FIELD_NAME, configuration.maxCapacity() - 1,
              LAST_PERMIT_GRANTED_FIELD_NAME, FirestoreUtil.timestampFromInstant(currentTime),
              configuration.expirationFieldName(),
              getBucketExpirationTimestamp(configuration.maxCapacity() - 1, currentTime)));

          maybeNextPermitAvailableTime = Optional.empty();
        }

        return maybeNextPermitAvailableTime;
      }, executor);
    }), executor);
  }

  /**
   * Returns the number of permits currently available in the current bucket.
   *
   * @param initialPermitsAvailable the permits available in the bucket at the time the most recent permit was granted
   * @param lastPermitGranted the time at which the most recent permit was granted
   *
   * @return the number of permits available from the given bucket at the current time
   */
  @VisibleForTesting
  double getCurrentPermitsAvailable(final double initialPermitsAvailable, final Instant lastPermitGranted) {
    final double elapsedNanos = Duration.between(lastPermitGranted, clock.instant()).toNanos();
    final double permitsRegenerated = elapsedNanos / configuration.permitRegenerationPeriod().toNanos();

    return Math.min(initialPermitsAvailable + permitsRegenerated, configuration.maxCapacity());
  }

  /**
   * Returns the time at which the desired number of permits will be available in a bucket.
   *
   * @param currentPermitsAvailable the number of permits currently available from the given bucket
   * @param desiredPermits the number of permits desired from the given bucket

   * @return the time at which the desired number of permits will be (or already was) available
   *
   * @see #getCurrentPermitsAvailable(double, Instant)
   */
  @VisibleForTesting
  Instant getPermitAvailabilityTime(final double currentPermitsAvailable, final int desiredPermits) {
    final double permitsNeeded = desiredPermits - currentPermitsAvailable;
    final double nanosPerPermit = configuration.permitRegenerationPeriod().toNanos();

    return clock.instant().plusNanos((long) (permitsNeeded * nanosPerPermit));
  }

  /**
   * Returns the time at which a bucket will completely regenerate its supply of permits and is no longer in a
   * "cooldown" state (and can therefore be deleted).
   *
   * @param currentPermitsAvailable the number of permits currently available from the given bucket
   * @param lastPermitGranted the time at which a permit was most recently granted from this bucket
   *
   * @return the time at which a bucket has regenerated all permits and exited its "cooldown" state
   *
   * @see #getCurrentPermitsAvailable(double, Instant)
   */
  @VisibleForTesting
  Timestamp getBucketExpirationTimestamp(final double currentPermitsAvailable, final Instant lastPermitGranted) {
    return FirestoreUtil.timestampFromInstant(latestOf(
        getPermitAvailabilityTime(currentPermitsAvailable, configuration.maxCapacity()),
        lastPermitGranted.plus(configuration.minDelay())));
  }

  @VisibleForTesting
  static Instant latestOf(final Instant a, final Instant b) {
    return b.isAfter(a) ? b : a;
  }
}
