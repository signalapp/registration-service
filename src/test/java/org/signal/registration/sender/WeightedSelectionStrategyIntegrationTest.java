/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.RegistrationService;
import org.signal.registration.sender.fictitious.FictitiousNumberVerificationCodeRepository;
import org.signal.registration.sender.fictitious.FictitiousNumberVerificationCodeSender;
import org.signal.registration.sender.infobip.classic.InfobipSmsSender;
import org.signal.registration.sender.messagebird.classic.MessageBirdSmsSender;
import org.signal.registration.sender.messagebird.classic.MessageBirdVoiceSender;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeRepository;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeSender;
import org.signal.registration.sender.twilio.classic.TwilioMessagingServiceSmsSender;
import org.signal.registration.sender.twilio.classic.TwilioVoiceSender;
import org.signal.registration.sender.twilio.verify.TwilioVerifySender;

@MicronautTest
@Property(name = "selection.sms.fallback-senders", value = "twilio-verify,twilio-programmable-messaging")
@Property(name = "selection.sms.default-weights.twilio-verify", value = "1")
@Property(name = "selection.sms.default-weights.infobip-sms", value = "0")
@Property(name = "selection.sms.region-weights.gl.messagebird-sms", value = "1")
@Property(name = "selection.sms.region-weights.gb.infobip-sms", value = "1")
@Property(name = "selection.sms.region-overrides.cn", value = "twilio-verify")
@Property(name = "selection.sms.region-overrides.mx", value = "twilio-programmable-messaging")
@Property(name = "selection.voice.fallback-senders", value = "twilio-verify,twilio-programmable-voice")
@Property(name = "selection.voice.default-weights.twilio-verify", value = "1")
@Property(name = "selection.voice.default-weights.messagebird-voice", value = "0")
@Property(name = "selection.voice.region-overrides.is", value = "messagebird-voice")
@Property(name = "selection.voice.region-overrides.cn", value = "twilio-verify")
@Property(name = "selection.voice.region-overrides.mx", value = "twilio-programmable-voice")
@Property(name = "selection.voice.unavailable-in-regions", value = "sy")
@Property(name = "adaptive.sms.default-choices", value = "twilio-verify,infobip-sms")
@Property(name = "adaptive.voice.default-choices", value = "twilio-verify,messagebird-voice")
@Property(name = "twilio.account-sid", value = "account-sid")
@Property(name = "twilio.api-key-sid", value = "api-key-sid")
@Property(name = "twilio.api-key-secret", value = "api-key-secret")
@Property(name = "twilio.messaging.nanpa-messaging-service-sid", value = "nanpa-messaging-service-sid")
@Property(name = "twilio.messaging.global-messaging-service-sid", value = "global-messaging-service-sid")
@Property(name = "twilio.verify.service-sid", value = "verify-service-sid")
@Property(name = "twilio.verify.android-app-hash", value = "android-app-hash")
@Property(name = "twilio.verify.supported-languages", value = "en,de")
@Property(name = "twilio.voice.supported-languages", value = "en,de,es")
@Property(name = "twilio.voice.phone-numbers", value = "+12025550123")
@Property(name = "twilio.voice.cdn-uri", value = "https://test.signal.org/")
@Property(name = "verification.sms.android-app-hash", value = "android-app-hash")
@Property(name = "verification.sms.supported-languages", value = "en")
@Property(name = "messagebird.access-key", value = "access-key")
@Property(name = "messagebird.default-sender-id", value = "test")
@Property(name = "infobip.default-sender-id", value="default")
@Property(name = "infobip.api-key", value="api-key")
@Property(name = "infobip.base-url", value="https://mmmex9.us.api.com")
@Property(name = "sinch.sms.client.service-plan-id", value="servicePlanId")
@Property(name = "sinch.sms.client.api-token", value="apiToken")
@Property(name = "sinch.sms.client.sms-region", value="US")
@Property(name = "sinch.default-sender-id", value="default")
class WeightedSelectionStrategyIntegrationTest {

  @MockBean(RegistrationService.class)
  RegistrationService registrationService() {
    return mock(RegistrationService.class);
  }

  @MockBean
  PrescribedVerificationCodeRepository prescribedVerificationCodeRepository() {
    final PrescribedVerificationCodeRepository repository = mock(PrescribedVerificationCodeRepository.class);
    when(repository.getVerificationCodes()).thenReturn(Collections.emptyMap());
    return repository;
  }

  @MockBean
  FictitiousNumberVerificationCodeRepository fictitiousNumberVerificationCodeRepository =
      mock(FictitiousNumberVerificationCodeRepository.class);

  @Inject
  private DynamicSenderSelectionStrategy selectionStrategy;

  @Inject
  PrescribedVerificationCodeRepository prescribedVerificationCodeRepository;

  @Inject
  PrescribedVerificationCodeSender prescribedVerificationCodeSender;

  private static final Phonenumber.PhoneNumber PRESCRIBED_CODE_NUMBER =
      PhoneNumberUtil.getInstance().getExampleNumber("US");

  private static final Phonenumber.PhoneNumber NON_PRESCRIBED_CODE_NUMBER =
      PhoneNumberUtil.getInstance().getExampleNumber("CA");

  private static final Phonenumber.PhoneNumber ALWAYS_USE_VERIFY_NUMBER =
      PhoneNumberUtil.getInstance().getExampleNumber("CN");

  private static final Phonenumber.PhoneNumber NEVER_USE_VERIFY_NUMBER =
      PhoneNumberUtil.getInstance().getExampleNumber("MX");

  private static final Phonenumber.PhoneNumber USE_MB_VOICE_NUMBER =
      PhoneNumberUtil.getInstance().getExampleNumber("IS");

  private static final Phonenumber.PhoneNumber USE_MB_SMS_NUMBER =
      PhoneNumberUtil.getInstance().getExampleNumber("GL");

  private static final Phonenumber.PhoneNumber USE_INFOBIP_SMS_NUMBER =
      PhoneNumberUtil.getInstance().getExampleNumber("GB");

  private static final Phonenumber.PhoneNumber NO_SENDER_AVAILABLE_NUMBER =
      PhoneNumberUtil.getInstance().getExampleNumber("SY");

  private static final Phonenumber.PhoneNumber FICTITIOUS_PHONE_NUMBER;

  static {
    try {
      FICTITIOUS_PHONE_NUMBER = PhoneNumberUtil.getInstance().parse("+12025550123", null);
    } catch (final NumberParseException e) {
      // This should never happen for a literally-specified, known-good phone number
      throw new AssertionError(e);
    }
  }

  @BeforeEach
  void setUp() {
    when(prescribedVerificationCodeRepository.getVerificationCodes())
        .thenReturn(Map.of(PRESCRIBED_CODE_NUMBER, "123456"));

    prescribedVerificationCodeSender.refreshPhoneNumbers();
  }

  @ParameterizedTest
  @MethodSource
  void chooseVerificationCodeSender(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final String acceptLanguage,
      final ClientType clientType,
      final Class<? extends VerificationCodeSender> senderClass) throws NoSenderAvailableException {

    assertEquals(senderClass,
        selectionStrategy.chooseVerificationCodeSender(
            messageTransport, phoneNumber, Locale.LanguageRange.parse(acceptLanguage), clientType, null, Collections.emptySet())
            .sender()
            .getClass());
  }

  private static Stream<Arguments> chooseVerificationCodeSender() {
    return Stream.of(
        Arguments.of(MessageTransport.SMS,   NON_PRESCRIBED_CODE_NUMBER,  "de", ClientType.IOS, TwilioVerifySender.class),
        Arguments.of(MessageTransport.SMS,   NON_PRESCRIBED_CODE_NUMBER,  "fr", ClientType.IOS, TwilioVerifySender.class),
        Arguments.of(MessageTransport.VOICE, NON_PRESCRIBED_CODE_NUMBER,  "de", ClientType.IOS, TwilioVerifySender.class),
        Arguments.of(MessageTransport.VOICE, NON_PRESCRIBED_CODE_NUMBER,  "fr", ClientType.IOS, TwilioVerifySender.class),
        Arguments.of(MessageTransport.VOICE, NON_PRESCRIBED_CODE_NUMBER,  "es", ClientType.IOS, TwilioVoiceSender.class),
        Arguments.of(MessageTransport.SMS,   PRESCRIBED_CODE_NUMBER,      "en", ClientType.IOS, PrescribedVerificationCodeSender.class),
        Arguments.of(MessageTransport.SMS,   FICTITIOUS_PHONE_NUMBER,     "en", ClientType.IOS, FictitiousNumberVerificationCodeSender.class),
        Arguments.of(MessageTransport.SMS,   ALWAYS_USE_VERIFY_NUMBER,    "de", ClientType.IOS, TwilioVerifySender.class),
        Arguments.of(MessageTransport.SMS,   ALWAYS_USE_VERIFY_NUMBER,    "fr", ClientType.IOS, TwilioVerifySender.class),
        Arguments.of(MessageTransport.SMS,   NEVER_USE_VERIFY_NUMBER,     "de", ClientType.IOS, TwilioMessagingServiceSmsSender.class),
        Arguments.of(MessageTransport.SMS,   NEVER_USE_VERIFY_NUMBER,     "fr", ClientType.IOS, TwilioMessagingServiceSmsSender.class),
        Arguments.of(MessageTransport.VOICE, NEVER_USE_VERIFY_NUMBER,     "de", ClientType.IOS, TwilioVoiceSender.class),
        Arguments.of(MessageTransport.VOICE, NEVER_USE_VERIFY_NUMBER,     "fr", ClientType.IOS, TwilioVoiceSender.class),
        Arguments.of(MessageTransport.VOICE, USE_MB_VOICE_NUMBER,         "en", ClientType.IOS, MessageBirdVoiceSender.class),
        Arguments.of(MessageTransport.SMS,   USE_MB_SMS_NUMBER,           "en", ClientType.IOS, MessageBirdSmsSender.class),
        Arguments.of(MessageTransport.SMS,   USE_INFOBIP_SMS_NUMBER,      "en", ClientType.IOS, InfobipSmsSender.class));
  }

  @Test
  void chooseVerificationCodeNoSenderAvailable() {
    assertThrows(NoSenderAvailableException.class, () -> selectionStrategy.chooseVerificationCodeSender(
        MessageTransport.VOICE, NO_SENDER_AVAILABLE_NUMBER, Locale.LanguageRange.parse("en"), ClientType.IOS, null,
        Collections.emptySet()));
  }
}
