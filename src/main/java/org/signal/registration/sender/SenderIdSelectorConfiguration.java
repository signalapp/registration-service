/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender;

import java.util.Map;

/**
 * A sender ID configuration defines which sender IDs should be used by {@link VerificationCodeSender} implementations
 * to choose a sender ID for a specific region.
 */
public interface SenderIdSelectorConfiguration {
  /**
   * The default sender ID to use if none exist for a given region.
   */
  String defaultSenderId();

  /**
   * A map of region code to sender IDs.
   */
  Map<String, String> senderIdsByRegion();
}
