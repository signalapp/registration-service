/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.classic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.sender.ClientType;

class TwilioMessagingServiceSmsSenderTest {

  private TwilioMessagingServiceSmsSender sender;

  @BeforeEach
  void setUp() {
    final TwilioMessagingConfiguration configuration = new TwilioMessagingConfiguration();
    configuration.setGlobalMessagingServiceSid("global-sid");
    configuration.setNanpaMessagingServiceSid("nanpa-sid");
    configuration.setAndroidAppHash("android-app-hash");
    configuration.setSupportedLanguages(List.of("en"));

    sender = new TwilioMessagingServiceSmsSender(new TwilioVerificationCodeGenerator(), configuration);
  }

  @ParameterizedTest
  @MethodSource
  void getMessageBody(final int countryCode, final ClientType clientType, final Locale locale) {
    final String verificationCode = new TwilioVerificationCodeGenerator().generateVerificationCode();

    final String messageBody =
        assertDoesNotThrow(() -> sender.getMessageBody(countryCode, clientType, verificationCode, locale));

    assertTrue(messageBody.contains(verificationCode));
  }

  private static Stream<Arguments> getMessageBody() {
    return Stream.of(
        Arguments.of(1, ClientType.IOS, Locale.US),
        Arguments.of(1, ClientType.ANDROID_WITHOUT_FCM, Locale.US),
        Arguments.of(1, ClientType.ANDROID_WITH_FCM, Locale.US),
        Arguments.of(1, ClientType.UNKNOWN, Locale.US),
        Arguments.of(1, ClientType.UNKNOWN, null),
        Arguments.of(86, ClientType.IOS, Locale.CHINA),
        Arguments.of(86, ClientType.ANDROID_WITHOUT_FCM, Locale.CHINA),
        Arguments.of(86, ClientType.ANDROID_WITH_FCM, Locale.CHINA),
        Arguments.of(86, ClientType.UNKNOWN, Locale.CHINA),
        Arguments.of(86, ClientType.UNKNOWN, null)
    );
  }

  @Test
  void getMessageBodyChina() {
    assertFalse(sender.getMessageBody(1, ClientType.UNKNOWN, "123456", Locale.FRANCE).contains("\u2008"));
    assertTrue(sender.getMessageBody(86, ClientType.UNKNOWN, "123456", Locale.FRANCE).contains("\u2008"));
  }
}
