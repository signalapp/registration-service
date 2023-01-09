/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import java.util.concurrent.CompletableFuture;

/**
 * A rate limiter limits the number of times in some period of time that an action identified by a given key may be
 * taken.
 *
 * @param <K> the type of key that identifies a rate-limited action
 */
public interface RateLimiter<K> {

  /**
   * Checks whether the action identified by the given key may be taken.
   *
   * @param key a key that identifies the action to be taken
   *
   * @return a future that either completes normally (in which case the action is permitted) or fails with a
   * {@link RateLimitExceededException}
   */
  CompletableFuture<Void> checkRateLimit(K key);
}
