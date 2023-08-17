/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.twilio.type.InboundSmsPrice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.Money;
import org.signal.registration.rpc.MessageTransport;
import reactor.core.publisher.Flux;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractTwilioSmsPriceEstimatorTest {

    private static final Currency USD = Currency.getInstance("USD");

    private static class TestPriceEstimator extends AbstractTwilioSmsPriceEstimator {

        private final List<InboundSmsPrice.Type> numberTypes;

        TestPriceEstimator(final TwilioSmsPriceProvider dataSource, final List<InboundSmsPrice.Type> numberTypes) {
            super(dataSource);
            this.numberTypes = numberTypes;
        }

        @Override
        protected List<InboundSmsPrice.Type> getNumberTypes(final AttemptPendingAnalysis attemptPendingAnalysis) {
            return numberTypes;
        }
    }

    @Test
    void getAveragePrices() {
        final EnumMap<InboundSmsPrice.Type, List<Money>> prices = new EnumMap<>(Map.of(
                InboundSmsPrice.Type.LOCAL, List.of(new Money(new BigDecimal("0.50"), USD), new Money(new BigDecimal("1.00"), USD)),
                InboundSmsPrice.Type.SHORTCODE, List.of(new Money(new BigDecimal("0.50"), USD)),
                InboundSmsPrice.Type.TOLLFREE, List.of()
        ));

        final EnumMap<InboundSmsPrice.Type, Money> expectedAverages = new EnumMap<>(Map.of(
                InboundSmsPrice.Type.LOCAL, new Money(new BigDecimal("0.75"), USD),
                InboundSmsPrice.Type.SHORTCODE, new Money(new BigDecimal("0.50"), USD)
        ));

        assertEquals(expectedAverages, TwilioVerifyPriceEstimator.getAveragePrices(prices));
    }

    @ParameterizedTest
    @MethodSource
    void estimatePrice(final List<TwilioSmsPrice> prices,
                       final List<InboundSmsPrice.Type> numberTypes,
                       final AttemptPendingAnalysis attemptPendingAnalysis,
                       @Nullable final String mcc,
                       @Nullable final String mnc,
                       @Nullable Money expectedEstimatedPrice) {

        final TwilioSmsPriceProvider priceProvider = mock(TwilioSmsPriceProvider.class);
        when(priceProvider.getPricingData()).thenReturn(Flux.fromIterable(prices));

        final TestPriceEstimator priceEstimator = new TestPriceEstimator(priceProvider, numberTypes);
        priceEstimator.refreshPrices();

        assertEquals(Optional.ofNullable(expectedEstimatedPrice), priceEstimator.estimatePrice(attemptPendingAnalysis, mcc, mnc));
    }

    private static Stream<Arguments> estimatePrice() {
        final List<TwilioSmsPrice> prices = List.of(
                new TwilioSmsPrice("US", "111", "111", new EnumMap<>(Map.of(InboundSmsPrice.Type.SHORTCODE, new Money(new BigDecimal("1.00"), USD), InboundSmsPrice.Type.LOCAL, new Money(new BigDecimal("2.00"), USD)))),
                new TwilioSmsPrice("US", "222", "222", new EnumMap<>(Map.of(InboundSmsPrice.Type.SHORTCODE, new Money(new BigDecimal("5.00"), USD)))),
                new TwilioSmsPrice("US", "333", "333", new EnumMap<>(Map.of(InboundSmsPrice.Type.TOLLFREE, new Money(new BigDecimal("9.00"), USD))))
        );

        final List<InboundSmsPrice.Type> numberTypes = List.of(InboundSmsPrice.Type.SHORTCODE, InboundSmsPrice.Type.LOCAL);

        return Stream.of(
                // Direct MCC/MNC match
                Arguments.of(prices, numberTypes,
                        AttemptPendingAnalysis.newBuilder()
                                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                                .setRegion("US")
                                .build(),
                        "111", "111", new Money(new BigDecimal("1.00"), USD)),

                // Direct MCC/MNC match, second-choice number type
                Arguments.of(prices, numberTypes,
                        AttemptPendingAnalysis.newBuilder()
                                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                                .setRegion("US")
                                .build(),
                        "222", "222", new Money(new BigDecimal("5.00"), USD)),

                // Regional match, but no MCC/MNC match
                Arguments.of(prices, numberTypes,
                        AttemptPendingAnalysis.newBuilder()
                                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                                .setRegion("US")
                                .build(),
                        "999", "999", new Money(new BigDecimal("3.00"), USD)),

                // Regional match, but no MCC/MNC specified
                Arguments.of(prices, numberTypes,
                        AttemptPendingAnalysis.newBuilder()
                                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                                .setRegion("US")
                                .build(),
                        null, null, new Money(new BigDecimal("3.00"), USD)),

                // MCC/MNC match, but no appropriate number type
                Arguments.of(prices, numberTypes,
                        AttemptPendingAnalysis.newBuilder()
                                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                                .setRegion("US")
                                .build(),
                        "333", "333", null),

                // No match for region, MCC, or MNC
                Arguments.of(prices, numberTypes,
                        AttemptPendingAnalysis.newBuilder()
                                .setMessageTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                                .setRegion("CA")
                                .build(),
                        "444", "444", null)
        );
    }
}
