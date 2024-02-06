/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.infobip;

import com.infobip.ApiException;
import com.infobip.api.SmsApi;
import com.infobip.model.SmsPrice;
import com.infobip.model.SmsReport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.arrow.util.VisibleForTesting;
import org.signal.registration.analytics.AbstractAttemptAnalyzer;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.analytics.Money;
import org.signal.registration.cli.bigtable.BigtableInfobipDefaultSmsPricesRepository;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.infobip.classic.InfobipSmsSender;
import org.signal.registration.util.CompletionExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Singleton
class InfobipSmsAttemptAnalyzer extends AbstractAttemptAnalyzer {
  private final SmsApi infobipSmsApiClient;
  private final Executor executor;
  private final BigtableInfobipDefaultSmsPricesRepository defaultSmsPricesRepository;
  private final Currency defaultPriceCurrency;
  private final MeterRegistry meterRegistry;
  private static final Logger logger = LoggerFactory.getLogger(InfobipSmsAttemptAnalyzer.class);
  private static final int MIN_MCC_MNC_LENGTH = 5;

  protected InfobipSmsAttemptAnalyzer(
      final AttemptPendingAnalysisRepository repository,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock,
      final SmsApi infobipSmsApiClient,
      @Named(TaskExecutors.IO) final Executor executor,
      final BigtableInfobipDefaultSmsPricesRepository defaultSmsPricesRepository,
      @Value("${analytics.infobip.sms.default-price-currency:USD}") final String defaultPriceCurrency,
      final MeterRegistry meterRegistry) {
    super(repository, attemptAnalyzedEventPublisher, clock);
    this.infobipSmsApiClient = infobipSmsApiClient;
    this.executor = executor;
    this.defaultSmsPricesRepository = defaultSmsPricesRepository;
    this.defaultPriceCurrency = Currency.getInstance(defaultPriceCurrency);
    this.meterRegistry = meterRegistry;
  }

  @Override
  protected String getSenderName() {
    return InfobipSmsSender.SENDER_NAME;
  }

  @Override
  @Scheduled(fixedDelay = "${analytics.infobip.sms.analysis-interval:4h}")
  protected void analyzeAttempts() {
    super.analyzeAttempts();
  }

  @Override
  protected CompletableFuture<AttemptAnalysis> analyzeAttempt(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return getSmsReport(attemptPendingAnalysis)
        .thenApply(maybeReport -> {
          final Optional<Money> maybeActualPrice = maybeReport.flatMap(report -> extractPrice(report.getPrice()));
          final Optional<MccMnc> maybeMccMnc = maybeReport.map(report -> MccMnc.fromString(report.getMccMnc()));
          final Optional<Money> maybeEstimatedPriceByMccMnc = maybeMccMnc.flatMap(mccMnc -> estimatePrice(mccMnc.toString()));
          return new AttemptAnalysis(
                  maybeActualPrice,
                  maybeEstimatedPriceByMccMnc.or(() -> estimatePrice(attemptPendingAnalysis.getRegion())),
                  maybeMccMnc.map(MccMnc::mcc),
                  maybeMccMnc.map(MccMnc::mnc));
        })
        .exceptionally(ignored -> AttemptAnalysis.EMPTY);
  }

  private CompletableFuture<Optional<SmsReport>> getSmsReport(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return CompletableFuture.supplyAsync(() -> {
      final List<SmsReport> smsReports;
      try {
        smsReports = infobipSmsApiClient.getOutboundSmsMessageDeliveryReports()
            .messageId(attemptPendingAnalysis.getRemoteId()).execute().getResults();
      } catch (ApiException e) {
        meterRegistry.counter(MetricsUtil.name(getClass(), "infobipException"),
                "statusCode", String.valueOf(e.responseStatusCode()),
                "error", e.details().getMessageId()).increment();
        throw CompletionExceptions.wrap(e);
      }

      if (smsReports == null || smsReports.isEmpty()) {
        meterRegistry.counter(MetricsUtil.name(getClass(), "emptyResults")).increment();
        return Optional.empty();
      }

      if (smsReports.size() > 1) {
        meterRegistry.counter(MetricsUtil.name(getClass(), "moreThanOneSmsReport"),
                "numReports", String.valueOf(smsReports.size())).increment();
        logger.debug("More than one SMS report with message IDs {}", smsReports.stream().map(SmsReport::getMessageId).toList());
      }

      return smsReports.stream()
          .filter(smsReport -> {
            final boolean hasPrice = smsReport.getPrice() != null;
            meterRegistry.counter(MetricsUtil.name(getClass(), "smsReport"), "hasPriceObject", String.valueOf(hasPrice)).increment();
            return hasPrice;
          })
          .findFirst();
    }, executor);
  }

  private Optional<Money> extractPrice(final SmsPrice smsPrice) {
    final boolean hasPriceData = smsPrice.getPricePerMessage() != null && smsPrice.getCurrency() != null;
    meterRegistry.counter(MetricsUtil.name(getClass(), "extractPrice"), "hasPriceData", String.valueOf(hasPriceData)).increment();
    return hasPriceData
            ? Optional.of(new Money(BigDecimal.valueOf(smsPrice.getPricePerMessage()), Currency.getInstance(smsPrice.getCurrency())))
            : Optional.empty();
  }

  private Optional<Money> estimatePrice(final String key) {
    return defaultSmsPricesRepository.get(key)
        .map(price -> new Money(price, defaultPriceCurrency));
  }

  @VisibleForTesting
  record MccMnc(String mcc, String mnc) {
    private static final MccMnc EMPTY = new MccMnc(null, null);

    @VisibleForTesting
    static MccMnc fromString(@Nullable final String mccMnc) {
      if (mccMnc == null) {
        return EMPTY;
      }

      if (mccMnc.length() < MIN_MCC_MNC_LENGTH) {
        logger.debug("Invalid mccMnc string {}", mccMnc);
        return EMPTY;
      }

      // Mobile country code is always 3 digits: https://en.wikipedia.org/wiki/Mobile_country_code
      return new MccMnc(mccMnc.substring(0, 3), mccMnc.substring(3));
    }
    
    public String toString() {
      return mcc + mnc;
    }
  }
}
