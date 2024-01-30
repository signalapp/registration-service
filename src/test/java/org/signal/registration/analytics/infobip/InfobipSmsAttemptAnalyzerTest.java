/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.infobip;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class InfobipSmsAttemptAnalyzerTest {
  @ParameterizedTest
  @MethodSource
  void parseMccMnc(final String mccMnc, final InfobipSmsAttemptAnalyzer.MccMnc expectedMccMnc) {
    assertEquals(expectedMccMnc, InfobipSmsAttemptAnalyzer.MccMnc.fromString(mccMnc));
  }

  private static Stream<Arguments> parseMccMnc() {
    return Stream.of(
        Arguments.of("2401", new InfobipSmsAttemptAnalyzer.MccMnc(null, null)),
        Arguments.of("24015", new InfobipSmsAttemptAnalyzer.MccMnc("240", "15")),
        Arguments.of(null, new InfobipSmsAttemptAnalyzer.MccMnc(null, null))
    );
  }
}
