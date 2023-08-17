/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.twilio.type.InboundSmsPrice;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.Money;
import org.signal.registration.rpc.MessageTransport;
import reactor.core.publisher.Flux;

class TwilioVerifyPriceEstimatorTest {

  @Test
  void estimatePrice() {
    final Money verifyFee = new Money(new BigDecimal("0.05"), Currency.getInstance("USD"));
    final Money basePrice = new Money(new BigDecimal("1.00"), verifyFee.currency());
    final TwilioSmsPriceProvider priceProvider = mock(TwilioSmsPriceProvider.class);

    final String region = "US";
    final String mcc = "111";
    final String mnc = "111";

    when(priceProvider.getPricingData()).thenReturn(Flux.just(
        new TwilioSmsPrice(region, mcc, mnc, new EnumMap<>(Map.of(InboundSmsPrice.Type.SHORTCODE, basePrice)))));

    final TwilioVerifyPriceEstimator priceEstimator =
        new TwilioVerifyPriceEstimator(priceProvider, verifyFee.amount(), verifyFee.currency().getCurrencyCode());

    priceEstimator.refreshPrices();

    {
      final AttemptPendingAnalysis unsuccessfulAttempt = AttemptPendingAnalysis.newBuilder()
          .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
          .setRegion(region)
          .setVerified(false)
          .build();

      assertEquals(Optional.of(basePrice), priceEstimator.estimatePrice(unsuccessfulAttempt, mcc, mnc));
    }

    {
      final AttemptPendingAnalysis successfulAttempt = AttemptPendingAnalysis.newBuilder()
          .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
          .setRegion(region)
          .setVerified(true)
          .build();

      assertEquals(Optional.of(basePrice.add(verifyFee)), priceEstimator.estimatePrice(successfulAttempt, mcc, mnc));
    }
  }

  @ParameterizedTest
  @MethodSource
  void getNumberTypes(final int attemptId, final List<InboundSmsPrice.Type> expectedNumberTypes) {
    final TwilioVerifyPriceEstimator priceEstimator =
        new TwilioVerifyPriceEstimator(mock(TwilioSmsPriceProvider.class), new BigDecimal("1.00"), "USD");

    assertEquals(expectedNumberTypes, priceEstimator.getNumberTypes(AttemptPendingAnalysis.newBuilder()
        .setAttemptId(attemptId)
        .build()));
  }

  private static Stream<Arguments> getNumberTypes() {
    return Stream.of(
        Arguments.of(0, List.of(InboundSmsPrice.Type.SHORTCODE, InboundSmsPrice.Type.LOCAL)),
        Arguments.of(1, List.of(InboundSmsPrice.Type.LOCAL))
    );
  }
}
