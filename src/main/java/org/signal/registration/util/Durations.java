/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.util;

import java.time.Duration;

public final class Durations {

  private Durations() {
  }

  /**
   * Tests whether a duration is has a non-zero, non-negative magnitude. Note that {@code Duration#isPositive()} is part
   * of the Java Duration API as of Java 18.
   *
   * @return {@code true} if the given duration has a non-zero, non-negative magnitude or {@code false} otherwise
   */
  public static boolean isPositive(final Duration duration) {
    return !(duration.isZero() || duration.isNegative());
  }
}
