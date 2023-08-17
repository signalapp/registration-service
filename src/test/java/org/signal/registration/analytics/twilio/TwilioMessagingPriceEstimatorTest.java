/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.twilio.type.InboundSmsPrice;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.analytics.AttemptPendingAnalysis;

class TwilioMessagingPriceEstimatorTest {

  @ParameterizedTest
  @MethodSource
  void getNumberTypes(final String region,
      final List<InboundSmsPrice.Type> nanpaNumberTypes,
      final List<InboundSmsPrice.Type> defaultNumberTypes,
      final Map<String, List<InboundSmsPrice.Type>> regionalNumberTypes,
      final List<InboundSmsPrice.Type> expectedNumberTypes) {

    final TwilioMessagingPriceEstimator priceEstimator =
        new TwilioMessagingPriceEstimator(mock(TwilioSmsPriceProvider.class),
            new TwilioMessagingPriceEstimatorConfiguration(nanpaNumberTypes, defaultNumberTypes, regionalNumberTypes));

    assertEquals(expectedNumberTypes, priceEstimator.getNumberTypes(AttemptPendingAnalysis.newBuilder()
        .setRegion(region)
        .build()));
  }

  private static Stream<Arguments> getNumberTypes() {
    final List<InboundSmsPrice.Type> nanpaNumberTypes = List.of(InboundSmsPrice.Type.TOLLFREE);
    final List<InboundSmsPrice.Type> defaultNumberTypes = List.of(InboundSmsPrice.Type.SHORTCODE);
    final List<InboundSmsPrice.Type> germanNumberTypes = List.of(InboundSmsPrice.Type.MOBILE);
    final Map<String, List<InboundSmsPrice.Type>> regionalNumberTypes = Map.of("DE", germanNumberTypes);

    return Stream.of(
        // NANPA number
        Arguments.of("US", nanpaNumberTypes, defaultNumberTypes, regionalNumberTypes, nanpaNumberTypes),

        // Regional override
        Arguments.of("DE", nanpaNumberTypes, defaultNumberTypes, regionalNumberTypes, germanNumberTypes),

        // Neither NANPA nor regional override
        Arguments.of("CL", nanpaNumberTypes, defaultNumberTypes, regionalNumberTypes, defaultNumberTypes)
    );
  }
}
