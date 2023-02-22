/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit.firestore;

import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
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
  private final Clock clock;

  private final String collectionName;
  private final String expirationFieldName;
  private final int maxCapacity;
  private final Duration permitRegenerationPeriod;
  private final Duration minDelay;

  private static final String AVAILABLE_PERMITS_FIELD_NAME = "available-permits";
  private static final String LAST_PERMIT_GRANTED_FIELD_NAME = "last-permit-granted";

  public FirestoreLeakyBucketRateLimiter(final Firestore firestore,
                                         @Named(TaskExecutors.IO) final Executor executor,
                                         final Clock clock,
                                         final String collectionName,
                                         final String expirationFieldName,
                                         final int maxCapacity,
                                         final Duration permitRegenerationPeriod,
                                         final Duration minDelay) {

    this.firestore = firestore;
    this.executor = executor;
    this.clock = clock;

    this.collectionName = collectionName;
    this.expirationFieldName = expirationFieldName;
    this.maxCapacity = maxCapacity;
    this.permitRegenerationPeriod = permitRegenerationPeriod;
    this.minDelay = minDelay;
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
  public CompletableFuture<Optional<Instant>> getTimeOfNextAction(final K key) {
    final DocumentReference documentReference =
        firestore.collection(collectionName).document(getDocumentId(key));

    return FirestoreUtil.toCompletableFuture(
        ApiFutures.transform(documentReference.get(),
            documentSnapshot -> Optional.of(getTimeOfNextAction(documentSnapshot)),
            executor),
        executor);
  }

  private Instant getTimeOfNextAction(final DocumentSnapshot documentSnapshot) {
    final Instant currentTime = clock.instant();
    final Instant nextActionAllowed;

    if (documentSnapshot.exists()) {
      final Instant lastPermitGranted = getLastPermitGranted(documentSnapshot, currentTime);
      final double currentAvailablePermits = getCurrentPermitsAvailable(documentSnapshot, lastPermitGranted);
      final Instant nextTimeToTakePermit = lastPermitGranted.plus(minDelay);
      final Instant permitAvailableTime = getPermitAvailabilityTime(currentAvailablePermits, 1);

      // We could be waiting on one, both, or neither of permit availability or the between-action cooldown.
      nextActionAllowed = latestOf(nextTimeToTakePermit, permitAvailableTime);
    } else {
      // No document exists, so we can assume that the action is permitted immediately
      nextActionAllowed = currentTime;
    }

    return nextActionAllowed;
  }

  @Override
  public CompletableFuture<Void> checkRateLimit(final K key) {
    return takePermit(key)
        .thenAccept(timeOfNextAction -> {
          final Instant currentTime = clock.instant();

          if (timeOfNextAction.isAfter(currentTime)) {
            throw new CompletionException(new RateLimitExceededException(Duration.between(currentTime, timeOfNextAction)));
          }
        });
  }

  @VisibleForTesting
  CompletableFuture<Instant> takePermit(final K key) {
    return FirestoreUtil.toCompletableFuture(firestore.runAsyncTransaction(transaction -> {
      final DocumentReference documentReference =
          firestore.collection(collectionName).document(getDocumentId(key));

      return ApiFutures.transform(transaction.get(documentReference), documentSnapshot -> {
        final Instant currentTime = clock.instant();
        final Instant timeOfNextAction;

        if (documentSnapshot.exists()) {
          timeOfNextAction = getTimeOfNextAction(documentSnapshot);

          if (!timeOfNextAction.isAfter(currentTime)) {
            // The caller may take the action now, so update the document to reflect that we used a permit
            final Instant lastPermitGranted = getLastPermitGranted(documentSnapshot, currentTime);
            final double currentAvailablePermits = getCurrentPermitsAvailable(documentSnapshot, lastPermitGranted);

            transaction.update(documentReference, Map.of(
                AVAILABLE_PERMITS_FIELD_NAME, currentAvailablePermits - 1,
                LAST_PERMIT_GRANTED_FIELD_NAME, FirestoreUtil.timestampFromInstant(currentTime),
                expirationFieldName,
                getBucketExpirationTimestamp(currentAvailablePermits - 1, lastPermitGranted)));
          }
        } else {
          // No document exists, which we interpret to be a "blank slate" and assume the action is allowed immediately
          timeOfNextAction = currentTime;

          transaction.create(documentReference, Map.of(
              AVAILABLE_PERMITS_FIELD_NAME, maxCapacity - 1,
              LAST_PERMIT_GRANTED_FIELD_NAME, FirestoreUtil.timestampFromInstant(currentTime),
              expirationFieldName,
              getBucketExpirationTimestamp(maxCapacity - 1, currentTime)));
        }

        return timeOfNextAction;
      }, executor);
    }), executor);
  }

  private Instant getLastPermitGranted(final DocumentSnapshot documentSnapshot, final Instant currentTime) {
    final Timestamp lastPermitGrantedTimestamp = documentSnapshot.getTimestamp(LAST_PERMIT_GRANTED_FIELD_NAME);

    return lastPermitGrantedTimestamp != null ?
        FirestoreUtil.instantFromTimestamp(lastPermitGrantedTimestamp) : currentTime;
  }

  private double getCurrentPermitsAvailable(final DocumentSnapshot documentSnapshot, final Instant lastPermitGranted) {
    final Double permitsAvailableFromDocument = documentSnapshot.getDouble(AVAILABLE_PERMITS_FIELD_NAME);

    return permitsAvailableFromDocument != null ?
        getCurrentPermitsAvailable(permitsAvailableFromDocument, lastPermitGranted) :
        maxCapacity;
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
    final double permitsRegenerated = elapsedNanos / permitRegenerationPeriod.toNanos();

    return Math.min(initialPermitsAvailable + permitsRegenerated, maxCapacity);
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
    final double nanosPerPermit = permitRegenerationPeriod.toNanos();

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
        getPermitAvailabilityTime(currentPermitsAvailable, maxCapacity),
        lastPermitGranted.plus(minDelay)));
  }

  @VisibleForTesting
  static Instant latestOf(final Instant a, final Instant b) {
    return b.isAfter(a) ? b : a;
  }
}
