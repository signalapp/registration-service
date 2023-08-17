/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.messagebird;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.Money;
import org.signal.registration.rpc.MessageTransport;
import reactor.core.publisher.Flux;

class MessageBirdPriceEstimatorTest {

  private static final Currency EUR = Currency.getInstance("EUR");

  @ParameterizedTest
  @MethodSource
  void estimatePrice(final List<MessageBirdSmsPrice> prices,
      final AttemptPendingAnalysis attemptPendingAnalysis,
      @Nullable final String mcc,
      @Nullable final String mnc,
      @Nullable Money expectedEstimatedPrice) {

    final MessageBirdSmsPriceProvider priceProvider = mock(MessageBirdSmsPriceProvider.class);
    when(priceProvider.getPrices()).thenReturn(Flux.fromIterable(prices));

    final MessageBirdPriceEstimator priceEstimator = new MessageBirdPriceEstimator(priceProvider);
    priceEstimator.refreshPrices();

    assertEquals(Optional.ofNullable(expectedEstimatedPrice), priceEstimator.estimatePrice(attemptPendingAnalysis, mcc, mnc));
  }

  private static Stream<Arguments> estimatePrice() {
    final List<MessageBirdSmsPrice> prices = List.of(
        new MessageBirdSmsPrice(null, null, null, new Money(new BigDecimal("0.060000"), EUR)),
        new MessageBirdSmsPrice("GR", "202", null, new Money(new BigDecimal("0.047000"), EUR)),
        new MessageBirdSmsPrice("GR", "202", "05", new Money(new BigDecimal("0.045000"), EUR)));

    return Stream.of(
        // MCC/MNC match
        Arguments.of(prices,
            AttemptPendingAnalysis.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setRegion("GR")
                .build(),
            "202", "05", new Money(new BigDecimal("0.045000"), EUR)),

        // MCC/MNC mismatch, but region match
        Arguments.of(prices,
            AttemptPendingAnalysis.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setRegion("GR")
                .build(),
            "202", "06", new Money(new BigDecimal("0.047000"), EUR)),

        // No match
        Arguments.of(prices,
            AttemptPendingAnalysis.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setRegion("US")
                .build(),
            "123", "456", new Money(new BigDecimal("0.060000"), EUR)),

        // No match and no default price
        Arguments.of(List.of(),
            AttemptPendingAnalysis.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .setRegion("GR")
                .build(),
            "202", "05", null),

        // MCC/MNC match, but unsupported transport
        Arguments.of(prices,
            AttemptPendingAnalysis.newBuilder()
                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
                .setRegion("GR")
                .build(),
            "202", "05", null)
    );
  }
}
