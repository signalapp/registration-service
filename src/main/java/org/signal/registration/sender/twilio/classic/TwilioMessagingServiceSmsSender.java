/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.classic;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import io.micronaut.context.MessageSource;
import io.micronaut.context.i18n.ResourceBundleMessageSource;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.UnsupportedMessageTransportException;
import org.signal.registration.sender.VerificationCodeGenerator;
import org.signal.registration.sender.VerificationCodeSender;

/**
 * A concrete implementation of an {@code AbstractTwilioProvidedCodeSender} that sends its codes via the Twilio
 * Programmable Messaging API.
 */
@Singleton
public class TwilioMessagingServiceSmsSender extends AbstractTwilioProvidedCodeSender implements VerificationCodeSender {

  private final TwilioRestClient twilioRestClient;
  private final VerificationCodeGenerator verificationCodeGenerator;
  private final TwilioMessagingConfiguration configuration;

  private final MessageSource messageSource;

  @VisibleForTesting
  static final String IOS_MESSAGE_KEY = "twilio.messaging.sms.ios";

  @VisibleForTesting
  static final String ANDROID_MESSAGE_KEY = "twilio.messaging.sms.android";

  @VisibleForTesting
  static final String GENERIC_MESSAGE_KEY = "twilio.messaging.sms.generic";

  private static final int COUNTRY_CODE_CN = 86;

  @Inject
  public TwilioMessagingServiceSmsSender(final TwilioRestClient twilioRestClient,
      final VerificationCodeGenerator verificationCodeGenerator,
      final TwilioMessagingConfiguration configuration) {

    this(twilioRestClient, verificationCodeGenerator, configuration,
        new ResourceBundleMessageSource("org.signal.registration.twilio.messaging.sms"));
  }

  @VisibleForTesting
  TwilioMessagingServiceSmsSender(final TwilioRestClient twilioRestClient,
      final VerificationCodeGenerator verificationCodeGenerator,
      final TwilioMessagingConfiguration configuration,
      MessageSource messageSource) {

    this.twilioRestClient = twilioRestClient;
    this.verificationCodeGenerator = verificationCodeGenerator;
    this.configuration = configuration;
    this.messageSource = messageSource;
  }

  @Override
  public String getName() {
    return "twilio-programmable-messaging";
  }

  @Override
  public Duration getSessionTtl() {
    return configuration.getSessionTtl();
  }

  @Override
  public boolean supportsDestination(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    return messageTransport == MessageTransport.SMS;
  }

  @Override
  public CompletableFuture<byte[]> sendVerificationCode(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) throws UnsupportedMessageTransportException {

    if (messageTransport != MessageTransport.SMS) {
      throw new UnsupportedMessageTransportException();
    }

    final Locale locale;
    {
      final String preferredLanguage = Locale.lookupTag(languageRanges, configuration.getSupportedLanguages());

      if (StringUtils.isNotBlank(preferredLanguage)) {
        locale = Locale.forLanguageTag(preferredLanguage);
      } else {
        locale = null;
      }
    }

    final String messagingServiceSid = phoneNumber.getCountryCode() == 1 ?
        configuration.getNanpaMessagingServiceSid() : configuration.getGlobalMessagingServiceSid();

    final String verificationCode = verificationCodeGenerator.generateVerificationCode();

    return Message.creator(twilioNumberFromPhoneNumber(phoneNumber), messagingServiceSid,
            getMessageBody(phoneNumber, clientType, verificationCode, locale))
        .createAsync(twilioRestClient)
        .whenComplete((message, throwable) -> incrementApiCallCounter("message.create", throwable))
        .thenApply(ignored -> buildSessionData(verificationCode));
  }

  @VisibleForTesting
  String getMessageBody(final Phonenumber.PhoneNumber phoneNumber,
      final ClientType clientType,
      final String verificationCode,
      final Locale locale) {

    final String messageKey = switch (clientType) {
      case IOS -> IOS_MESSAGE_KEY;
      case ANDROID_WITH_FCM -> ANDROID_MESSAGE_KEY;
      default -> GENERIC_MESSAGE_KEY;
    };

    final String regionCode = PhoneNumberUtil.getInstance().getRegionCodeForNumber(phoneNumber).toUpperCase();

    final Optional<String> maybeMessageKeyWithVariant =
        Optional.ofNullable(configuration.getVerificationMessageVariants().get(regionCode))
            .map(variant -> getMessageKeyForVariant(messageKey, variant));

    final MessageSource.MessageContext messageContext =
        MessageSource.MessageContext.of(locale, Map.of(
            "code", verificationCode,
            "appHash", configuration.getAndroidAppHash()));

    final String message = maybeMessageKeyWithVariant
        .flatMap(keyWithVariant -> messageSource.getMessage(keyWithVariant, messageContext))
        .orElseGet(() -> messageSource.getRequiredMessage(messageKey, messageContext));

    // Twilio recommends adding this character to the end of strings delivered to China because some carriers in China
    // are blocking GSM-7 encoding and this will force Twilio to send using UCS-2 instead.
    return phoneNumber.getCountryCode() == COUNTRY_CODE_CN ? message + "\u2008" : message;
  }

  @VisibleForTesting
  static String getMessageKeyForVariant(final String baseMessageKey, final String variant) {
    return baseMessageKey + "." + variant;
  }
}
