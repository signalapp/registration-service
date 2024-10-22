/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics;

import org.reactivestreams.Publisher;
import org.signal.registration.sender.VerificationCodeSender;
import java.util.concurrent.CompletableFuture;

/**
 * An "attempt pending analysis" repository stores partial information about completed verification attempts with the
 * expectation that another component will provide additional information (price, for example) later. Implementations
 * should store attempts pending analysis on a best-effort basis and may discard them at any time for any reason.
 */
public interface AttemptPendingAnalysisRepository {

  /**
   * Stores an attempt pending analysis. If an attempt pending analysis already exists with the given sender name and
   * remote ID, it will be overwritten by the given event.
   *
   * @param attemptPendingAnalysis the attempt pending analysis to be stored
   *
   * @return a future that completes when the attempt pending analysis has been stored
   */
  CompletableFuture<Void> store(AttemptPendingAnalysis attemptPendingAnalysis);

  /**
   * Returns a publisher that yields all attempts pending analysis for the given sender.
   *
   * @param senderName the name of the sender for which to retrieve attempts pending analysis
   *
   * @return a publisher that yields all attempts pending analysis for the given sender
   *
   * @see VerificationCodeSender#getName()
   */
  Publisher<AttemptPendingAnalysis> getBySender(String senderName);

  /**
   * Removes an individual attempt pending analysis from this repository. Has no effect if the given attempt does not
   * exist within this repository.
   *
   * @param attemptPendingAnalysis the attempt to remove from this repository
   *
   * @return a future that completes when the identified attempt pending analysis is no longer present in this
   * repository
   */
  CompletableFuture<Void> remove(AttemptPendingAnalysis attemptPendingAnalysis);
}
