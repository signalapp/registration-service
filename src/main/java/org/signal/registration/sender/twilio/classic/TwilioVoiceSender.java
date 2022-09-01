/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.classic;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.Phonenumber;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.type.PhoneNumber;
import com.twilio.type.Twiml;
import io.micronaut.context.MessageSource;
import io.micronaut.context.i18n.ResourceBundleMessageSource;
import jakarta.inject.Singleton;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.VerificationCodeSender;

/**
 * A concrete implementation of an {@code AbstractTwilioProvidedCodeSender} that sends its codes via the Twilio
 * Programmable Voice API.
 */
@Singleton
public class TwilioVoiceSender extends AbstractTwilioProvidedCodeSender implements VerificationCodeSender {

  private final TwilioVerificationCodeGenerator verificationCodeGenerator;
  private final TwilioVoiceConfiguration configuration;

  private final MessageSource twimlMessageSource =
      new ResourceBundleMessageSource("org.signal.registration.twilio.voice.twiml");

  private static final String DEFAULT_LANGUAGE = "en-US";

  public TwilioVoiceSender(final TwilioVerificationCodeGenerator verificationCodeGenerator,
      final TwilioVoiceConfiguration configuration) {

    this.verificationCodeGenerator = verificationCodeGenerator;
    this.configuration = configuration;
  }

  @Override
  public MessageTransport getTransport() {
    return MessageTransport.VOICE;
  }

  @Override
  public Duration getSessionTtl() {
    return configuration.getSessionTtl();
  }

  @Override
  public boolean supportsDestination(final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    return Locale.lookupTag(languageRanges, configuration.getSupportedLanguages()) != null;
  }

  @Override
  public CompletableFuture<byte[]> sendVerificationCode(final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    final PhoneNumber fromPhoneNumber = configuration.getPhoneNumbers().get(ThreadLocalRandom.current()
        .nextInt(configuration.getPhoneNumbers().size()));

    final String languageTag = Optional.ofNullable(Locale.lookupTag(languageRanges, configuration.getSupportedLanguages()))
        .orElse(DEFAULT_LANGUAGE);

    final String verificationCode = verificationCodeGenerator.generateVerificationCode();

    return Call.creator(twilioNumberFromPhoneNumber(phoneNumber), fromPhoneNumber, buildCallTwiml(verificationCode, languageTag))
        .createAsync()
        .thenApply(ignored -> buildSessionData(verificationCode));
  }

  @VisibleForTesting
  Twiml buildCallTwiml(final String verificationCode, final String languageTag) {
    final URI cdnUriWithLocale = configuration.getCdnUri().resolve("/" + languageTag + "/");

    return new Twiml(twimlMessageSource.getRequiredMessage("twilio.voice.twiml",
        MessageSource.MessageContext.of(Map.of(
        "verification", cdnUriWithLocale.resolve("verification.mp3"),
        "code0", cdnUriWithLocale.resolve(verificationCode.charAt(0) + "_middle.mp3"),
        "code1", cdnUriWithLocale.resolve(verificationCode.charAt(1) + "_middle.mp3"),
        "code2", cdnUriWithLocale.resolve(verificationCode.charAt(2) + "_middle.mp3"),
        "code3", cdnUriWithLocale.resolve(verificationCode.charAt(3) + "_middle.mp3"),
        "code4", cdnUriWithLocale.resolve(verificationCode.charAt(4) + "_middle.mp3"),
        "code5", cdnUriWithLocale.resolve(verificationCode.charAt(5) + "_falling.mp3")
    ))));
  }
}
