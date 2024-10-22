/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics;

import com.google.common.annotations.VisibleForTesting;
import io.micronaut.context.event.ApplicationEventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.signal.registration.sender.VerificationCodeSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * An abstract attempt analyzer periodically reads attempts pending analysis from a
 * {@link AttemptPendingAnalysisRepository} for a specific {@link org.signal.registration.sender.VerificationCodeSender},
 * and attempts to analyze each attempt individually. When an attempt pending analysis is analyzed successfully, it is
 * removed from the repository and an {@link AttemptAnalyzedEvent} is triggered.
 * <p>
 * Subclasses should call the {@link #analyzeAttempts()} method at regular intervals.
 */
public abstract class AbstractAttemptAnalyzer {

  private final AttemptPendingAnalysisRepository repository;
  private final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher;
  private final Clock clock;

  private final Logger logger = LoggerFactory.getLogger(getClass());

  @VisibleForTesting
  public static final Duration DEFAULT_PRICING_DEADLINE = Duration.ofHours(36);

  protected AbstractAttemptAnalyzer(final AttemptPendingAnalysisRepository repository,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock) {

    this.repository = repository;
    this.attemptAnalyzedEventPublisher = attemptAnalyzedEventPublisher;
    this.clock = clock;
  }

  protected void analyzeAttempts() {
    logger.debug("Processing attempts pending analysis");

    Flux.from(repository.getBySender(getSenderName()))
            .flatMap(attemptPendingAnalysis -> Mono.fromFuture(analyzeAttempt(attemptPendingAnalysis))
                .map(analysis -> new AttemptAnalyzedEvent(attemptPendingAnalysis, analysis)))
        .filter(attemptAnalyzedEvent -> {
          final Instant attemptTimestamp = Instant.ofEpochMilli(attemptAnalyzedEvent.attemptPendingAnalysis().getTimestampEpochMillis());
          final boolean pricingDeadlinePassed = clock.instant().isAfter(attemptTimestamp.plus(getPricingDeadline()));

          return attemptAnalyzedEvent.attemptAnalysis().price().isPresent() || pricingDeadlinePassed;
        })
        .subscribe(attemptAnalyzedEvent -> {
          repository.remove(attemptAnalyzedEvent.attemptPendingAnalysis());
          attemptAnalyzedEventPublisher.publishEvent(attemptAnalyzedEvent);
        });
  }

  /**
   * Returns the name of the {@link org.signal.registration.sender.VerificationCodeSender} whose attempts pending
   * analysis will be processed by this analyzer.
   *
   * @return the name of the verification code sender whose attempts pending analysis will be processed by this analyzer
   */
  protected abstract String getSenderName();

  /**
   * Returns the age of an attempt pending analysis after which we presume the provider will not be able to provide
   * pricing data, and the analyzer should proceed without pricing data from the provider.
   */
  protected Duration getPricingDeadline() {
    return DEFAULT_PRICING_DEADLINE;
  }

  /**
   * Attempts to retrieve additional details (presumably from an external service provider) about an attempt pending
   * analysis. If no details are available, callers should return {@link AttemptAnalysis#EMPTY}.
   *
   * @param attemptPendingAnalysis the attempt for which to retrieve additional details
   *
   * @return an analysis of the attempt with whatever details are currently available
   *
   * @see VerificationCodeSender#getName()
   */
  protected abstract CompletableFuture<AttemptAnalysis> analyzeAttempt(final AttemptPendingAnalysis attemptPendingAnalysis);
}
