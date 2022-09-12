/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.signal.registration.RegistrationService;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeSender;
import org.signal.registration.sender.twilio.classic.TwilioMessagingServiceSmsSender;
import org.signal.registration.sender.twilio.classic.TwilioVoiceSender;
import org.signal.registration.sender.twilio.verify.TwilioVerifySender;

@MicronautTest
@Property(name = "twilio.account-sid", value = "account-sid")
@Property(name = "twilio.api-key-sid", value = "api-key-sid")
@Property(name = "twilio.api-key-secret", value = "api-key-secret")
@Property(name = "twilio.messaging.nanpa-messaging-service-sid", value = "nanpa-messaging-service-sid")
@Property(name = "twilio.messaging.global-messaging-service-sid", value = "global-messaging-service-sid")
@Property(name = "twilio.messaging.android-app-hash", value = "android-app-hash")
@Property(name = "twilio.messaging.supported-languages", value = "en")
@Property(name = "twilio.verify.service-sid", value = "verify-service-sid")
@Property(name = "twilio.verify.android-app-hash", value = "android-app-hash")
@Property(name = "twilio.verify.supported-languages", value = "en,de")
@Property(name = "twilio.voice.phone-numbers", value = "+12025550123")
@Property(name = "twilio.voice.cdn-uri", value = "https://test.signal.org/")
@Property(name = "twilio.voice.supported-languages", value = "en,de")
@Property(name = "prescribed-verification-codes.verification-codes.+12025554321", value = "123456")
class TwilioSenderSelectionStrategyTest {

  @MockBean(RegistrationService.class)
  RegistrationService registrationService() {
    return mock(RegistrationService.class);
  }

  @Inject
  private TwilioSenderSelectionStrategy selectionStrategy;

  @Test
  void chooseVerificationCodeSender() throws NumberParseException {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().parse("+12025550123", null);

    assertTrue(selectionStrategy.chooseVerificationCodeSender(
        MessageTransport.SMS, phoneNumber, Locale.LanguageRange.parse("de"), ClientType.IOS)
        instanceof TwilioVerifySender);

    assertTrue(selectionStrategy.chooseVerificationCodeSender(
        MessageTransport.SMS, phoneNumber, Locale.LanguageRange.parse("fr"), ClientType.IOS)
        instanceof TwilioMessagingServiceSmsSender);

    assertTrue(selectionStrategy.chooseVerificationCodeSender(
        MessageTransport.VOICE, phoneNumber, Locale.LanguageRange.parse("de"), ClientType.IOS)
        instanceof TwilioVerifySender);

    assertTrue(selectionStrategy.chooseVerificationCodeSender(
        MessageTransport.VOICE, phoneNumber, Locale.LanguageRange.parse("fr"), ClientType.IOS)
        instanceof TwilioVoiceSender);

    assertTrue(selectionStrategy.chooseVerificationCodeSender(
        MessageTransport.SMS, PhoneNumberUtil.getInstance().parse("+12025554321", null),
        Locale.LanguageRange.parse("en"), ClientType.IOS)
        instanceof PrescribedVerificationCodeSender);
  }
}
