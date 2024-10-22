/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.signal.registration.analytics.AbstractAttemptAnalyzer;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.sender.twilio.verify.TwilioVerifySender;

/**
 * Analyzes verification attempts from {@link TwilioVerifySender}.
 */
@Singleton
class TwilioVerifyAttemptAnalyzer extends AbstractAttemptAnalyzer {

  private final TwilioVerifyPriceEstimator twilioVerifyPriceEstimator;

  TwilioVerifyAttemptAnalyzer(final AttemptPendingAnalysisRepository repository,
      final TwilioVerifyPriceEstimator twilioVerifyPriceEstimator,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock) {

    super(repository, attemptAnalyzedEventPublisher, clock);

    this.twilioVerifyPriceEstimator = twilioVerifyPriceEstimator;
  }

  @Override
  @Scheduled(fixedDelay = "${analytics.twilio.verify.analysis-interval:5m}",
      initialDelay = "${analytics.twilio.verify.analysis-initial-delay:5m}")
  protected void analyzeAttempts() {
    super.analyzeAttempts();
  }

  @Override
  protected String getSenderName() {
    return TwilioVerifySender.SENDER_NAME;
  }

  @Override
  protected Duration getPricingDeadline() {
    // Always use estimated prices for Twilio Verify
    return Duration.ZERO;
  }

  @Override
  protected CompletableFuture<AttemptAnalysis> analyzeAttempt(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return CompletableFuture.completedFuture(new AttemptAnalysis(Optional.empty(),
        twilioVerifyPriceEstimator.estimatePrice(attemptPendingAnalysis, null, null),
        Optional.empty(),
        Optional.empty()));
  }
}
