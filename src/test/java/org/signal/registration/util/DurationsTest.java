/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationsTest {

  @Test
  void isPositive() {
    assertTrue(Durations.isPositive(Duration.ofMinutes(1)));
    assertFalse(Durations.isPositive(Duration.ZERO));
    assertFalse(Durations.isPositive(Duration.ofMinutes(-1)));
  }
}
