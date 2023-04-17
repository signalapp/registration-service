/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.configuration.metrics.annotation.RequiresMetrics;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.util.Currency;
import org.signal.registration.Environments;
import org.signal.registration.metrics.MetricsUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An attempt price metrics listener listens for analyzed verification attempts and increments a price counter for
 * events that include a price.
 */
@Singleton
@RequiresMetrics
@Requires(env = Environments.ANALYTICS)
class AttemptPriceMetricsListener implements ApplicationEventListener<AttemptAnalyzedEvent> {

  private final MeterRegistry meterRegistry;

  private static final Currency USD = Currency.getInstance("USD");
  private static final BigDecimal ONE_MILLION = new BigDecimal("1e6");

  private static final String COUNTER_NAME = MetricsUtil.name(AttemptPriceMetricsListener.class, "attemptPriceMicros");

  private static final Logger logger = LoggerFactory.getLogger(AttemptPriceMetricsListener.class);

  AttemptPriceMetricsListener(final MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void onApplicationEvent(final AttemptAnalyzedEvent event) {
    event.attemptAnalysis().price().ifPresent(price -> {
      if (USD.equals(price.currency())) {
        final String messageTransport = switch (event.attemptPendingAnalysis().getMessageTransport()) {
          case MESSAGE_TRANSPORT_SMS -> "sms";
          case MESSAGE_TRANSPORT_VOICE -> "voice";
          case MESSAGE_TRANSPORT_UNSPECIFIED, UNRECOGNIZED -> "unrecognized";
        };

        final String clientType = switch (event.attemptPendingAnalysis().getClientType()) {
          case CLIENT_TYPE_IOS -> "ios";
          case CLIENT_TYPE_ANDROID_WITH_FCM -> "android-with-fcm";
          case CLIENT_TYPE_ANDROID_WITHOUT_FCM -> "android-without-fcm";
          case CLIENT_TYPE_UNSPECIFIED, UNRECOGNIZED -> "unrecognized";
        };

        meterRegistry.counter(COUNTER_NAME,
                "sender", event.attemptPendingAnalysis().getSenderName(),
                "client", clientType,
                "transport", messageTransport,
                "verified", String.valueOf(event.attemptPendingAnalysis().getVerified()),
                "regionCode", event.attemptPendingAnalysis().getRegion())
            .increment(price.amount().multiply(ONE_MILLION).longValue());
      } else {
        logger.warn("Price provided in non-USD currency ({}) by {}", price.currency(), event.attemptPendingAnalysis().getSenderName());
      }
    });
  }
}
