/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.classic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.sender.ClientType;

class TwilioVoiceSenderTest {

  private TwilioVoiceSender twilioVoiceSender;

  @BeforeEach
  void setUp() {
    final TwilioVoiceConfiguration configuration = new TwilioVoiceConfiguration();
    configuration.setPhoneNumbers(List.of("+12025550123"));
    configuration.setCdnUri(URI.create("https://example.com/"));
    configuration.setSupportedLanguages(List.of("en", "de"));

    twilioVoiceSender = new TwilioVoiceSender(new TwilioVerificationCodeGenerator(), configuration);
  }

  @ParameterizedTest
  @MethodSource
  void supportsDestination(final List<Locale.LanguageRange> languageRanges,
      final boolean expectSupported) throws NumberParseException {

    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().parse("+12025559876", null);
    assertEquals(expectSupported, twilioVoiceSender.supportsDestination(phoneNumber, languageRanges, ClientType.UNKNOWN));
  }

  private static Stream<Arguments> supportsDestination() {
    return Stream.of(
        Arguments.of(Locale.LanguageRange.parse("en"), true),
        Arguments.of(Locale.LanguageRange.parse("de"), true),
        Arguments.of(Locale.LanguageRange.parse("fr"), false),
        Arguments.of(Collections.emptyList(), false));
  }

  @Test
  void buildCallTwiml() {
    final String twiml = twilioVoiceSender.buildCallTwiml("123456", "es").toString();

    assertTrue(twiml.contains("https://example.com/es/verification.mp3"));
    assertTrue(twiml.contains("https://example.com/es/1_middle.mp3"));
    assertTrue(twiml.contains("https://example.com/es/2_middle.mp3"));
    assertTrue(twiml.contains("https://example.com/es/3_middle.mp3"));
    assertTrue(twiml.contains("https://example.com/es/4_middle.mp3"));
    assertTrue(twiml.contains("https://example.com/es/5_middle.mp3"));
    assertTrue(twiml.contains("https://example.com/es/6_falling.mp3"));
  }
}
