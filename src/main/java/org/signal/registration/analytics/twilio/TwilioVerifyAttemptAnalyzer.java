/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.google.common.annotations.VisibleForTesting;
import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.verify.v2.VerificationAttempt;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.analytics.Money;
import org.signal.registration.sender.twilio.verify.TwilioVerifySender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Analyzes verification attempts from {@link TwilioVerifySender}.
 */
@Singleton
class TwilioVerifyAttemptAnalyzer {

  private final TwilioRestClient twilioRestClient;
  private final AttemptPendingAnalysisRepository repository;

  private final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher;

  private static final String CURRENCY_KEY = "currency";
  private static final String VALUE_KEY = "value";
  private static final String MCC_KEY = "mcc";
  private static final String MNC_KEY = "mnc";

  private static final Duration MAX_ATTEMPT_AGE = Duration.ofDays(2);
  private static final int PAGE_SIZE = 1_000;

  private static final Logger logger = LoggerFactory.getLogger(TwilioVerifyAttemptAnalyzer.class);

  public TwilioVerifyAttemptAnalyzer(final TwilioRestClient twilioRestClient,
      final AttemptPendingAnalysisRepository repository,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher) {

    this.twilioRestClient = twilioRestClient;
    this.repository = repository;
    this.attemptAnalyzedEventPublisher = attemptAnalyzedEventPublisher;
  }

  // Temporarily disabled pending upstream configuration
  // @Scheduled(fixedDelay = "${analytics.twilio.sms.analysis-interval:4h}")
  void analyzeAttempts() {
    // While most attempt analyzers fetch a stream of attempts pending analysis from our own repository and resolve them
    // one by one, the rate limits for the Twilio Verifications Attempt API (see
    // https://www.twilio.com/docs/verify/api/list-verification-attempts#rate-limits) prevent us from doing that here.
    // Instead, we fetch verification attempts from the Twilio API using fewer, larger pages and reconcile those against
    // what we have stored locally.
    try {
      VerificationAttempt.reader()
          .setDateCreatedAfter(ZonedDateTime.now().minus(MAX_ATTEMPT_AGE))
          .setPageSize(PAGE_SIZE)
          .read(twilioRestClient)
          .forEach(this::analyzeAttempt);
    } catch (final ApiException e) {
      logger.warn("Failed to retrieve verification attempts", e);
    }
  }

  @VisibleForTesting
  void analyzeAttempt(final VerificationAttempt verificationAttempt) {
    if (verificationAttempt.getPrice() != null
        && verificationAttempt.getPrice().get(VALUE_KEY) != null
        && verificationAttempt.getPrice().get(CURRENCY_KEY) != null) {

      repository.getByRemoteIdentifier(TwilioVerifySender.SENDER_NAME, verificationAttempt.getSid())
          .thenAccept(maybeAttemptPendingAnalysis -> maybeAttemptPendingAnalysis.ifPresent(attemptPendingAnalysis -> {
            Optional<Money> maybePrice;

            try {
              maybePrice = Optional.of(
                  new Money(new BigDecimal(verificationAttempt.getPrice().get(VALUE_KEY).toString()),
                      Currency.getInstance(verificationAttempt.getPrice().get(CURRENCY_KEY).toString().toUpperCase(Locale.ROOT))));
            } catch (final IllegalArgumentException e) {
              logger.warn("Failed to parse price: {}", verificationAttempt, e);
              maybePrice = Optional.empty();
            }

            final Optional<Map<String, Object>> maybeChannelData =
                Optional.ofNullable(verificationAttempt.getChannelData());

            final Optional<String> maybeMcc = maybeChannelData
                .map(channelData -> channelData.get(MCC_KEY))
                .map(mcc -> StringUtils.stripToNull(mcc.toString()));

            final Optional<String> maybeMnc = maybeChannelData
                .map(channelData -> channelData.get(MNC_KEY))
                .map(mnc -> StringUtils.stripToNull(mnc.toString()));

            if (maybePrice.isPresent()) {
              repository.remove(TwilioVerifySender.SENDER_NAME, verificationAttempt.getSid());

              attemptAnalyzedEventPublisher.publishEvent(
                  new AttemptAnalyzedEvent(attemptPendingAnalysis,
                      new AttemptAnalysis(maybePrice, maybeMcc, maybeMnc)));
            }
          }));
    }
  }
}