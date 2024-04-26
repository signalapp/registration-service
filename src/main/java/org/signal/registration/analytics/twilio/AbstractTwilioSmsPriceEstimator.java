/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.google.common.annotations.VisibleForTesting;
import com.twilio.type.InboundSmsPrice;
import io.micronaut.scheduling.annotation.Scheduled;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.Money;
import org.signal.registration.analytics.PriceEstimator;
import org.signal.registration.rpc.MessageTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

abstract class AbstractTwilioSmsPriceEstimator implements PriceEstimator {

  private final TwilioSmsPriceProvider dataSource;

  private volatile Map<String, EnumMap<InboundSmsPrice.Type, Money>> pricesByMccMnc = Collections.emptyMap();
  private volatile Map<String, EnumMap<InboundSmsPrice.Type, Money>> averagePricesByRegion = Collections.emptyMap();

  private final Logger logger = LoggerFactory.getLogger(getClass());

  AbstractTwilioSmsPriceEstimator(final TwilioSmsPriceProvider dataSource) {
    this.dataSource = dataSource;
  }

  @VisibleForTesting
  @Scheduled(fixedDelay = "${analytics.twilio.sms.price-estimate.refresh-interval:4h}")
  void refreshPrices() {
    final Map<String, EnumMap<InboundSmsPrice.Type, Money>> pricesByMccMnc = new HashMap<>();
    final Map<String, EnumMap<InboundSmsPrice.Type, List<Money>>> pricesByRegion = new HashMap<>();

    dataSource.getPricingData()
        .doOnNext(smsPrice -> {
          if (StringUtils.isNoneBlank(smsPrice.mcc(), smsPrice.mnc())) {
            pricesByMccMnc.put(smsPrice.mcc() + smsPrice.mnc(), smsPrice.pricesByNumberType());
          }

          if (StringUtils.isNotBlank(smsPrice.region())) {
            smsPrice.pricesByNumberType().forEach((numberType, price) -> {
              pricesByRegion.computeIfAbsent(smsPrice.region(), ignored -> new EnumMap<>(InboundSmsPrice.Type.class))
                  .computeIfAbsent(numberType, ignored -> new ArrayList<>())
                  .add(price);
            });
          }
        })
        .doOnError(throwable -> logger.error("Failed to refresh prices", throwable))
        .then()
        .block();

    final Map<String, EnumMap<InboundSmsPrice.Type, Money>> averagePricesByRegion = new HashMap<>();
    pricesByRegion.forEach((region, prices) -> averagePricesByRegion.put(region, getAveragePrices(prices)));

    this.pricesByMccMnc = pricesByMccMnc;
    this.averagePricesByRegion = averagePricesByRegion;
  }

  @VisibleForTesting
  static EnumMap<InboundSmsPrice.Type, Money> getAveragePrices(final EnumMap<InboundSmsPrice.Type, List<Money>> pricesByNumberType) {
    final EnumMap<InboundSmsPrice.Type, Money> averagePrices = new EnumMap<>(InboundSmsPrice.Type.class);

    pricesByNumberType.forEach((numberType, prices) -> prices.stream()
        .reduce(Money::add)
        .map(sum -> new Money(sum.amount().divide(BigDecimal.valueOf(prices.size()), RoundingMode.HALF_EVEN), sum.currency()))
        .ifPresent(average -> averagePrices.put(numberType, average)));

    return averagePrices;
  }

  @Override
  public Optional<Money> estimatePrice(final AttemptPendingAnalysis attemptPendingAnalysis,
      @Nullable final String mcc,
      @Nullable final String mnc) {

    if (attemptPendingAnalysis.getMessageTransport() != MessageTransport.MESSAGE_TRANSPORT_SMS) {
      return Optional.empty();
    }

    final Optional<EnumMap<InboundSmsPrice.Type, Money>> maybePriceByMccMnc = StringUtils.isNoneBlank(mcc, mnc)
        ? Optional.ofNullable(pricesByMccMnc.get(mcc + mnc))
        : Optional.empty();

    return maybePriceByMccMnc.or(
            () -> Optional.ofNullable(averagePricesByRegion.get(attemptPendingAnalysis.getRegion())))
        .flatMap(pricesByNumberType -> getNumberTypes(attemptPendingAnalysis).stream()
            .map(pricesByNumberType::get)
            .filter(Objects::nonNull)
            .findFirst());
  }

  /**
   * Returns a prioritized list of number types to try for the given verification attempt.
   *
   * @param attemptPendingAnalysis the verification attempt for which to infer sender number types
   *
   * @return a prioritized list of number types to try for the given verification attempt
   */
  protected abstract List<InboundSmsPrice.Type> getNumberTypes(final AttemptPendingAnalysis attemptPendingAnalysis);
}
