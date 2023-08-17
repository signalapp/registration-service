/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * A price estimator makes a best effort to estimate the cost to send a verification message.
 */
public interface PriceEstimator {

  /**
   * Estimates the cost to send a verification message for the given verification attempt.
   *
   * @param attemptPendingAnalysis the verification attempt for which to estimate a price
   * @param mcc the MCC to which the verification message was sent, if known; may be {@code null}
   * @param mnc the MNC to which the verification message was sent, if known; may be {@code null}
   *
   * @return an estimated price to send a verification message for the given attempt, or empty if an estimate was not
   * immediately available
   */
  Optional<Money> estimatePrice(AttemptPendingAnalysis attemptPendingAnalysis, @Nullable String mcc, @Nullable String mnc);
}
