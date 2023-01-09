/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import java.time.Duration;

/**
 * Indicates that some action was not permitted because one or more callers have attempted the action too frequently.
 * Callers that receive this exception may retry the action after the time indicated by
 * {@link #getRetryAfterDuration()}.
 */
public class RateLimitExceededException extends Exception {

  private final Duration retryAfterDuration;

  public RateLimitExceededException(final Duration retryAfterDuration) {
    super(null, null, true, false);

    this.retryAfterDuration = retryAfterDuration;
  }

  /**
   * Returns the next time at which the action blocked by this exception might succeed.
   *
   * @return the next time at which the action blocked by this exception might succeed
   */
  public Duration getRetryAfterDuration() {
    return retryAfterDuration;
  }
}
