/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.google.common.annotations.VisibleForTesting;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.verify.v2.VerificationAttempt;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.analytics.AbstractAttemptAnalyzer;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.analytics.Money;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.twilio.verify.TwilioVerifySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Analyzes verification attempts from {@link TwilioVerifySender}.
 */
@Singleton
class TwilioVerifyAttemptAnalyzer {

  private final TwilioRestClient twilioRestClient;
  private final AttemptPendingAnalysisRepository repository;
  private final TwilioVerifyPriceEstimator twilioVerifyPriceEstimator;

  private final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher;

  private final Clock clock;

  private final String verifyServiceSid;

  private final Counter attemptReadCounter;
  private final Counter attemptAnalyzedCounter;
  private final Counter fallbackAttemptEstimatedCounter;

  private static final String CURRENCY_KEY = "currency";
  private static final String VALUE_KEY = "value";
  private static final String MCC_KEY = "mcc";
  private static final String MNC_KEY = "mnc";

  @VisibleForTesting
  static final Duration MAX_ATTEMPT_AGE = Duration.ofHours(36);

  private static final int PAGE_SIZE = 1_000;

  private static final Logger logger = LoggerFactory.getLogger(TwilioVerifyAttemptAnalyzer.class);

  public TwilioVerifyAttemptAnalyzer(final TwilioRestClient twilioRestClient,
      final AttemptPendingAnalysisRepository repository,
      final TwilioVerifyPriceEstimator twilioVerifyPriceEstimator,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock,
      @Value("${twilio.verify.service-sid}") final String verifyServiceSid,
      final MeterRegistry meterRegistry) {

    this.twilioRestClient = twilioRestClient;
    this.repository = repository;
    this.twilioVerifyPriceEstimator = twilioVerifyPriceEstimator;
    this.attemptAnalyzedEventPublisher = attemptAnalyzedEventPublisher;
    this.clock = clock;

    this.verifyServiceSid = verifyServiceSid;

    this.attemptReadCounter = meterRegistry.counter(MetricsUtil.name(getClass(), "attemptRead"));
    this.attemptAnalyzedCounter = meterRegistry.counter(MetricsUtil.name(getClass(), "attemptAnalyzed"), "fallback", "false");
    this.fallbackAttemptEstimatedCounter = meterRegistry.counter(MetricsUtil.name(getClass(), "attemptAnalyzed"), "fallback", "true");
  }

  @Scheduled(fixedDelay = "${analytics.twilio.verify.analysis-interval:4h}")
  void analyzeAttempts() {
    // While most attempt analyzers fetch a stream of attempts pending analysis from our own repository and resolve them
    // one by one, the rate limits for the Twilio Verifications Attempt API (see
    // https://www.twilio.com/docs/verify/api/list-verification-attempts#rate-limits) prevent us from doing that here.
    // Instead, we fetch verification attempts from the Twilio API using fewer, larger pages and reconcile those against
    // what we have stored locally.
    analyzeAttempts(ReaderUtil.readerToFlux(VerificationAttempt.reader()
        .setVerifyServiceSid(verifyServiceSid)
        .setDateCreatedAfter(ZonedDateTime.now().minus(MAX_ATTEMPT_AGE))
        .setPageSize(PAGE_SIZE), twilioRestClient));

    // The Verification Attempts API is not guaranteed to produce results for every attempt. Rather than letting
    // attempts simply evaporate, fall back to estimated pricing for attempts that have "aged out."
    fallbackAnalyzeAttempts(Flux.from(repository.getBySender(TwilioVerifySender.SENDER_NAME)));
  }

  @VisibleForTesting
  void analyzeAttempts(final Flux<VerificationAttempt> verificationAttempts) {
    verificationAttempts
        .doOnNext(ignored -> attemptReadCounter.increment())
        .filter(verificationAttempt -> {
          final boolean pricingDeadlineExpired =
              clock.instant().isAfter(verificationAttempt.getDateCreated().plus(AbstractAttemptAnalyzer.PRICING_DEADLINE).toInstant());

          return hasPrice(verificationAttempt) || pricingDeadlineExpired;
        })
        .flatMap(verificationAttempt -> Mono.fromFuture(
                repository.getByRemoteIdentifier(TwilioVerifySender.SENDER_NAME, verificationAttempt.getSid()))
            .flatMap(Mono::justOrEmpty)
            .flatMap(attemptPendingAnalysis -> {
              Optional<Money> maybePrice = Optional
                  .of(verificationAttempt)
                  .filter(TwilioVerifyAttemptAnalyzer::hasPrice)
                  .flatMap(attempt -> {
                    try {
                      return Optional.of(
                          new Money(new BigDecimal(verificationAttempt.getPrice().get(VALUE_KEY).toString()),
                              Currency.getInstance(
                                  verificationAttempt.getPrice().get(CURRENCY_KEY).toString().toUpperCase(Locale.ROOT))));
                    } catch (final IllegalArgumentException e) {
                      logger.warn("Failed to parse price: {}", verificationAttempt, e);
                      return Optional.empty();
                    }
                  });

              final Optional<Map<String, Object>> maybeChannelData =
                  Optional.ofNullable(verificationAttempt.getChannelData());

              final Optional<String> maybeMcc = maybeChannelData
                  .map(channelData -> channelData.get(MCC_KEY))
                  .map(mcc -> StringUtils.stripToNull(mcc.toString()));

              final Optional<String> maybeMnc = maybeChannelData
                  .map(channelData -> channelData.get(MNC_KEY))
                  .map(mnc -> StringUtils.stripToNull(mnc.toString()));

              return Mono.just(new AttemptAnalyzedEvent(attemptPendingAnalysis,
                  new AttemptAnalysis(maybePrice,
                      twilioVerifyPriceEstimator.estimatePrice(attemptPendingAnalysis, maybeMcc.orElse(null), maybeMnc.orElse(null)),
                      maybeMcc,
                      maybeMnc)));
            }))
        .doOnNext(analyzedAttempt -> {
          attemptAnalyzedCounter.increment();

          repository.remove(TwilioVerifySender.SENDER_NAME, analyzedAttempt.attemptPendingAnalysis().getRemoteId());
          attemptAnalyzedEventPublisher.publishEvent(analyzedAttempt);
        })
        .doOnError(throwable -> logger.error("Unexpected error when fetching verification attempts", throwable))
        .then()
        .block();
  }

  @VisibleForTesting
  void fallbackAnalyzeAttempts(final Flux<AttemptPendingAnalysis> attemptsPendingAnalysis) {
    final Instant fallbackAttemptThreshold = clock.instant().minus(MAX_ATTEMPT_AGE);

    attemptsPendingAnalysis
        .filter(attemptPendingAnalysis -> Instant.ofEpochMilli(attemptPendingAnalysis.getTimestampEpochMillis()).isBefore(fallbackAttemptThreshold))
        .map(fallbackAttemptPendingAnalysis -> new AttemptAnalyzedEvent(fallbackAttemptPendingAnalysis, new AttemptAnalysis(Optional.empty(),
            twilioVerifyPriceEstimator.estimatePrice(fallbackAttemptPendingAnalysis, null, null),
            Optional.empty(),
            Optional.empty())))
        .doOnNext(attemptAnalyzedEvent -> {
          fallbackAttemptEstimatedCounter.increment();

          repository.remove(TwilioVerifySender.SENDER_NAME, attemptAnalyzedEvent.attemptPendingAnalysis().getRemoteId());
          attemptAnalyzedEventPublisher.publishEvent(attemptAnalyzedEvent);
        })
        .then()
        .block();
  }

  private static boolean hasPrice(final VerificationAttempt verificationAttempt) {
    return verificationAttempt.getPrice() != null
        && verificationAttempt.getPrice().get(VALUE_KEY) != null
        && verificationAttempt.getPrice().get(CURRENCY_KEY) != null;
  }
}
