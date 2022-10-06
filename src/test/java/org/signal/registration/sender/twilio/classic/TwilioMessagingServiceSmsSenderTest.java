/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.classic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import com.google.i18n.phonenumbers.Phonenumber;
import com.twilio.http.TwilioRestClient;
import io.micronaut.context.MessageSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.UnsupportedMessageTransportException;
import org.signal.registration.sender.VerificationCodeGenerator;

class TwilioMessagingServiceSmsSenderTest {

  private TwilioMessagingServiceSmsSender sender;

  private static final Phonenumber.PhoneNumber US_NUMBER = PhoneNumberUtil.getInstance().getExampleNumber("US");
  private static final Phonenumber.PhoneNumber CN_NUMBER = PhoneNumberUtil.getInstance().getExampleNumber("CN");

  @BeforeEach
  void setUp() {
    final TwilioMessagingConfiguration configuration = new TwilioMessagingConfiguration();
    configuration.setGlobalMessagingServiceSid("global-sid");
    configuration.setNanpaMessagingServiceSid("nanpa-sid");
    configuration.setAndroidAppHash("android-app-hash");
    configuration.setSupportedLanguages(List.of("en"));

    sender = new TwilioMessagingServiceSmsSender(mock(TwilioRestClient.class),
        new VerificationCodeGenerator(),
        configuration);
  }

  @Test
  void sendVerificationCodeUnsupportedTransport() {
    assertThrows(UnsupportedMessageTransportException.class, () -> sender.sendVerificationCode(MessageTransport.VOICE,
        US_NUMBER,
        Collections.emptyList(),
        ClientType.UNKNOWN).join());
  }

  @ParameterizedTest
  @MethodSource
  void getMessageBody(final Phonenumber.PhoneNumber phoneNumber, final ClientType clientType, final Locale locale) {
    final String verificationCode = new VerificationCodeGenerator().generateVerificationCode();

    final String messageBody =
        assertDoesNotThrow(() -> sender.getMessageBody(phoneNumber, clientType, verificationCode, locale));

    assertTrue(messageBody.contains(verificationCode));
  }

  private static Stream<Arguments> getMessageBody() {
    return Stream.of(
        Arguments.of(US_NUMBER, ClientType.IOS, Locale.US),
        Arguments.of(US_NUMBER, ClientType.ANDROID_WITHOUT_FCM, Locale.US),
        Arguments.of(US_NUMBER, ClientType.ANDROID_WITH_FCM, Locale.US),
        Arguments.of(US_NUMBER, ClientType.UNKNOWN, Locale.US),
        Arguments.of(US_NUMBER, ClientType.UNKNOWN, null),
        Arguments.of(CN_NUMBER, ClientType.IOS, Locale.CHINA),
        Arguments.of(CN_NUMBER, ClientType.ANDROID_WITHOUT_FCM, Locale.CHINA),
        Arguments.of(CN_NUMBER, ClientType.ANDROID_WITH_FCM, Locale.CHINA),
        Arguments.of(CN_NUMBER, ClientType.UNKNOWN, Locale.CHINA),
        Arguments.of(CN_NUMBER, ClientType.UNKNOWN, null)
    );
  }

  @Test
  void getMessageBodyChina() {
    assertFalse(sender.getMessageBody(US_NUMBER, ClientType.UNKNOWN, "123456", Locale.FRANCE).contains("\u2008"));
    assertTrue(sender.getMessageBody(CN_NUMBER, ClientType.UNKNOWN, "123456", Locale.FRANCE).contains("\u2008"));
  }

  @Test
  void getMessageBodyWithVariant() {
    final String recognizedVariant = "fancy";
    final String unrecognizedVariant = "unrecognized";

    final String boringVerificationMessage = "This SMS is boring.";
    final String fancyVerificationMessage = "This SMS is fancy.";

    final TwilioMessagingConfiguration configuration = new TwilioMessagingConfiguration();
    configuration.setGlobalMessagingServiceSid("global-sid");
    configuration.setNanpaMessagingServiceSid("nanpa-sid");
    configuration.setAndroidAppHash("android-app-hash");
    configuration.setSupportedLanguages(List.of("en"));
    configuration.setVerificationMessageVariants(Map.of(
        "mx", recognizedVariant,
        "fr", unrecognizedVariant));

    final MessageSource messageSource = mock(MessageSource.class);

    when(messageSource.getRequiredMessage(
        eq(TwilioMessagingServiceSmsSender.GENERIC_MESSAGE_KEY),
        any(MessageSource.MessageContext.class)))
        .thenReturn(boringVerificationMessage);

    when(messageSource.getMessage(
        eq(TwilioMessagingServiceSmsSender.getMessageKeyForVariant(TwilioMessagingServiceSmsSender.GENERIC_MESSAGE_KEY, recognizedVariant)),
        any(MessageSource.MessageContext.class)))
        .thenReturn(Optional.of(fancyVerificationMessage));

    final TwilioMessagingServiceSmsSender mockMessageSourceSender =
        new TwilioMessagingServiceSmsSender(mock(TwilioRestClient.class),
            new VerificationCodeGenerator(),
            configuration,
            messageSource);

    final Phonenumber.PhoneNumber phoneNumberWithoutVariant = PhoneNumberUtil.getInstance().getExampleNumber("US");
    final Phonenumber.PhoneNumber phoneNumberWithVariant = PhoneNumberUtil.getInstance().getExampleNumber("MX");
    final Phonenumber.PhoneNumber phoneNumberWithUnrecognizedVariant = PhoneNumberUtil.getInstance().getExampleNumber("FR");

    assertEquals(boringVerificationMessage,
        mockMessageSourceSender.getMessageBody(phoneNumberWithoutVariant, ClientType.UNKNOWN, "123456", Locale.ENGLISH));

    assertEquals(fancyVerificationMessage,
        mockMessageSourceSender.getMessageBody(phoneNumberWithVariant, ClientType.UNKNOWN, "123456", Locale.ENGLISH));

    assertEquals(boringVerificationMessage,
        mockMessageSourceSender.getMessageBody(phoneNumberWithUnrecognizedVariant, ClientType.UNKNOWN, "123456", Locale.ENGLISH));
  }
}
