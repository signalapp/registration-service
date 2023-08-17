/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.messagebird;

import com.google.common.annotations.VisibleForTesting;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.Money;
import org.signal.registration.analytics.PriceEstimator;
import org.signal.registration.rpc.MessageTransport;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Singleton
class MessageBirdPriceEstimator implements PriceEstimator {

  private final MessageBirdSmsPriceProvider priceProvider;

  @Nullable
  private volatile Money defaultPrice = null;
  private volatile Map<String, Money> pricesByRegion = Collections.emptyMap();
  private volatile Map<String, Money> pricesByMccMnc = Collections.emptyMap();

  MessageBirdPriceEstimator(final MessageBirdSmsPriceProvider priceProvider) {
    this.priceProvider = priceProvider;
  }

  @VisibleForTesting
  @Scheduled(fixedDelay = "${analytics.messagebird.sms.pricing-refresh-interval:4h}")
  void refreshPrices() {
    final Map<String, Money> pricesByRegion = new HashMap<>();
    final Map<String, Money> pricesByMccMnc = new HashMap<>();

    for (final MessageBirdSmsPrice price : priceProvider.getPrices().collectList().blockOptional().orElseGet(List::of)) {
      if (price.region() == null) {
        this.defaultPrice = price.price();
      } else if (StringUtils.isNoneBlank(price.mcc(), price.mnc())) {
        pricesByMccMnc.put(price.mcc() + price.mnc(), price.price());
      } else {
        pricesByRegion.put(price.region(), price.price());
      }
    }

    this.pricesByRegion = pricesByRegion;
    this.pricesByMccMnc = pricesByMccMnc;
  }

  @Override
  public Optional<Money> estimatePrice(final AttemptPendingAnalysis attemptPendingAnalysis,
      @Nullable final String mcc,
      @Nullable final String mnc) {

    if (attemptPendingAnalysis.getMessageTransport() != MessageTransport.MESSAGE_TRANSPORT_SMS) {
      return Optional.empty();
    }

    final Optional<Money> maybePriceByMccMnc = StringUtils.isNoneBlank(mcc, mnc)
        ? Optional.ofNullable(pricesByMccMnc.get(mcc + mnc))
        : Optional.empty();

    return maybePriceByMccMnc
        .or(() -> Optional.ofNullable(pricesByRegion.get(attemptPendingAnalysis.getRegion())))
        .or(() -> Optional.ofNullable(defaultPrice));
  }
}
