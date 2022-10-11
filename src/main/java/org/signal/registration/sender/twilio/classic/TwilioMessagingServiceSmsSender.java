/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.classic;

import com.google.i18n.phonenumbers.Phonenumber;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.VerificationSmsBodyProvider;
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
  private final VerificationSmsBodyProvider verificationSmsBodyProvider;
  private final TwilioMessagingConfiguration configuration;

  public TwilioMessagingServiceSmsSender(final TwilioRestClient twilioRestClient,
      final VerificationCodeGenerator verificationCodeGenerator,
      final VerificationSmsBodyProvider verificationSmsBodyProvider,
      final TwilioMessagingConfiguration configuration) {

    this.twilioRestClient = twilioRestClient;
    this.verificationCodeGenerator = verificationCodeGenerator;
    this.verificationSmsBodyProvider = verificationSmsBodyProvider;
    this.configuration = configuration;
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
            verificationSmsBodyProvider.getVerificationSmsBody(phoneNumber, clientType, verificationCode, locale))
        .createAsync(twilioRestClient)
        .whenComplete((message, throwable) -> incrementApiCallCounter("message.create", throwable))
        .thenApply(ignored -> buildSessionData(verificationCode));
  }
}
