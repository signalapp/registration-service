/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.twilio.verify.TwilioVerifySender;
import reactor.core.publisher.Flux;

/**
 * Analyzes verification attempts from {@link TwilioVerifySender}.
 */
@Singleton
class TwilioVerifyAttemptAnalyzer {

  private final AttemptPendingAnalysisRepository repository;
  private final TwilioVerifyPriceEstimator twilioVerifyPriceEstimator;

  private final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher;

  private final Counter attemptEstimatedCounter;

  public TwilioVerifyAttemptAnalyzer(final AttemptPendingAnalysisRepository repository,
      final TwilioVerifyPriceEstimator twilioVerifyPriceEstimator,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final MeterRegistry meterRegistry) {

    this.repository = repository;
    this.twilioVerifyPriceEstimator = twilioVerifyPriceEstimator;
    this.attemptAnalyzedEventPublisher = attemptAnalyzedEventPublisher;

    this.attemptEstimatedCounter = meterRegistry.counter(MetricsUtil.name(getClass(), "attemptAnalyzed"));
  }

  @Scheduled(fixedDelay = "${analytics.twilio.verify.analysis-interval:5m}")
  void analyzeAttempts() {
    // Always use estimated prices for Twilio Verify
    estimatePricingForAttempts(Flux.from(repository.getBySender(TwilioVerifySender.SENDER_NAME)));
  }

  @VisibleForTesting
  void estimatePricingForAttempts(final Flux<AttemptPendingAnalysis> attemptsPendingAnalysis) {
    attemptsPendingAnalysis
        .map(attempt -> new AttemptAnalyzedEvent(attempt, new AttemptAnalysis(Optional.empty(),
            twilioVerifyPriceEstimator.estimatePrice(attempt, null, null),
            Optional.empty(),
            Optional.empty())))
        .doOnNext(attemptAnalyzedEvent -> {
          attemptEstimatedCounter.increment();

          repository.remove(TwilioVerifySender.SENDER_NAME, attemptAnalyzedEvent.attemptPendingAnalysis().getRemoteId());
          attemptAnalyzedEventPublisher.publishEvent(attemptAnalyzedEvent);
        })
        .then()
        .block();
  }
}
