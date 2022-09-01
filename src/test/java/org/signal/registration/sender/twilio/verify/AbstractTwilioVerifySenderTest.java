/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.sender.ClientType;

abstract class AbstractTwilioVerifySenderTest {

  protected static final TwilioVerifyConfiguration TWILIO_VERIFY_CONFIGURATION = new TwilioVerifyConfiguration();

  static {
    TWILIO_VERIFY_CONFIGURATION.setAndroidAppHash("app-hash");
    TWILIO_VERIFY_CONFIGURATION.setServiceSid("service-sid");
    TWILIO_VERIFY_CONFIGURATION.setServiceFriendlyName("friendly-name");
    TWILIO_VERIFY_CONFIGURATION.setSupportedLanguages(List.of("en"));
  }

  protected abstract AbstractTwilioVerifySender getSender();

  @ParameterizedTest
  @MethodSource
  void supportsDestination(final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType,
      final boolean expectSupportsDestination) {

    assertEquals(expectSupportsDestination, getSender().supportsDestination(phoneNumber, languageRanges, clientType));
  }

  private static Stream<Arguments> supportsDestination() throws NumberParseException {
    final Phonenumber.PhoneNumber phoneNumber =
        PhoneNumberUtil.getInstance().parse("+12025550123", null);

    return Stream.of(
        Arguments.of(phoneNumber, Locale.LanguageRange.parse("en"), ClientType.IOS, true),
        Arguments.of(phoneNumber, Locale.LanguageRange.parse("en"), ClientType.ANDROID_WITHOUT_FCM, true),
        Arguments.of(phoneNumber, Locale.LanguageRange.parse("en"), ClientType.ANDROID_WITH_FCM, true),
        Arguments.of(phoneNumber, Locale.LanguageRange.parse("en"), ClientType.UNKNOWN, true),
        Arguments.of(phoneNumber, Locale.LanguageRange.parse("ja"), ClientType.IOS, false),
        Arguments.of(phoneNumber, Locale.LanguageRange.parse("ja,en;q=0.4"), ClientType.IOS, true));
  }
}
