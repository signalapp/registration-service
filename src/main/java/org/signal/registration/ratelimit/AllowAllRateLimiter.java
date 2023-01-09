/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import java.util.concurrent.CompletableFuture;

/**
 * A trivial rate limiter implementation that allows all actions unconditionally. This implementation is intended only
 * for use in development and testing and should never be used in a production setting.
 *
 * @param <K> the type of key that identifies a rate-limited action
 */
class AllowAllRateLimiter<K> implements RateLimiter<K> {

  @Override
  public CompletableFuture<Void> checkRateLimit(final K key) {
    return CompletableFuture.completedFuture(null);
  }
}
