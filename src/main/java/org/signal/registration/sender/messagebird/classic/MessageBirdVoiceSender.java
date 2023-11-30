/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.messagebird.classic;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.InvalidProtocolBufferException;
import com.messagebird.MessageBirdClient;
import com.messagebird.exceptions.MessageBirdException;
import com.messagebird.objects.VoiceMessage;
import com.messagebird.objects.VoiceMessageResponse;
import io.micrometer.core.instrument.Timer;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.AttemptData;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderIdSelector;
import org.signal.registration.sender.UnsupportedMessageTransportException;
import org.signal.registration.sender.VerificationCodeGenerator;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.sender.VerificationTTSBodyProvider;
import org.signal.registration.sender.messagebird.MessageBirdClassicSessionData;
import org.signal.registration.sender.messagebird.MessageBirdExceptions;
import org.signal.registration.sender.messagebird.MessageBirdSenderConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends messages through the MessageBird Voice API
 * <p>
 * The <a href="https://developers.messagebird.com/api/voice-messaging/#voice-messaging-api"> MessageBird Voice
 * Messaging API</a> sends arbitrary messages through voice calls. Verification codes are generated by this class and
 * added to the message text, then later verified through the previously stored session.
 */
@Singleton
public class MessageBirdVoiceSender implements VerificationCodeSender {

  private static final Logger logger = LoggerFactory.getLogger(MessageBirdVoiceSender.class);

  private final MessageBirdVoiceConfiguration configuration;
  private final Map<String, String> supportedLanguages;
  private final Executor executor;
  private final VerificationCodeGenerator verificationCodeGenerator;
  private final VerificationTTSBodyProvider verificationTTSBodyProvider;
  private final MessageBirdClient client;
  private final ApiClientInstrumenter apiClientInstrumenter;
  private final SenderIdSelector senderIdSelector;

  public MessageBirdVoiceSender(
      final @Named(TaskExecutors.IO) Executor executor,
      final MessageBirdVoiceConfiguration configuration,
      final VerificationCodeGenerator verificationCodeGenerator,
      final VerificationTTSBodyProvider verificationTTSBodyProvider,
      final MessageBirdClient messageBirdClient,
      final ApiClientInstrumenter apiClientInstrumenter,
      final MessageBirdSenderConfiguration senderConfiguration) {
    this.configuration = configuration;
    this.supportedLanguages = supportedLanguages(configuration.supportedLanguages());
    this.executor = executor;
    this.verificationCodeGenerator = verificationCodeGenerator;
    this.verificationTTSBodyProvider = verificationTTSBodyProvider;
    this.client = messageBirdClient;
    this.apiClientInstrumenter = apiClientInstrumenter;
    this.senderIdSelector = new SenderIdSelector(senderConfiguration.senderIdsByRegion(), senderConfiguration.defaultSenderId());
  }

  @Override
  public String getName() {
    return "messagebird-voice";
  }

  @Override
  public Duration getAttemptTtl() {
    return this.configuration.sessionTtl();
  }

  @Override
  public boolean supportsTransport(final MessageTransport transport) {
    return transport == MessageTransport.VOICE;
  }

  @Override
  public boolean supportsLanguage(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges) {
    // the language is supported only if the upstream TTS API supports
    // it AND there's a translation for it
    return lookupMessageBirdLanguage(languageRanges).isPresent()
        && verificationTTSBodyProvider.supportsLanguage(languageRanges);
  }

  private Optional<String> lookupMessageBirdLanguage(final List<Locale.LanguageRange> languageRanges) {
    return Optional
        .ofNullable(Locale.lookupTag(languageRanges, supportedLanguages.keySet()))
        .map(supportedLanguages::get);
  }

  @Override
  public CompletableFuture<AttemptData> sendVerificationCode(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber, final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) throws UnsupportedMessageTransportException {
    final String e164 = PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);

    final String verificationCode = verificationCodeGenerator.generateVerificationCode();
    final String body = verificationTTSBodyProvider.getVerificationBody(phoneNumber, clientType, verificationCode, languageRanges);

    final VoiceMessage request = new VoiceMessage(body, e164);
    request.setOriginator(senderIdSelector.getSenderId(phoneNumber));
    request.setRepeat(configuration.messageRepeatCount());
    lookupMessageBirdLanguage(languageRanges).ifPresent(request::setLanguage);

    final Timer.Sample sample = Timer.start();

    return CompletableFuture.supplyAsync(() -> {
          try {
            final VoiceMessageResponse response = this.client.sendVoiceMessage(request);
            if (response.getRecipients().getTotalDeliveryFailedCount() != 0) {
              throw new CompletionException(new IOException("Failed to deliver voice message"));
            }
            return new AttemptData(
                Optional.of(response.getId()),
                MessageBirdClassicSessionData.newBuilder().setVerificationCode(verificationCode).build().toByteArray());
          } catch (MessageBirdException e) {
            logger.debug("Failed verification with {}, errors={}", e.getMessage(), e.getErrors());
            throw new CompletionException(e);
          }
        }, this.executor)
        .whenComplete((ignored, throwable) ->
            apiClientInstrumenter.recordApiCallMetrics(
                getName(),
                "call.create",
                throwable == null,
                MessageBirdExceptions.extract(throwable),
                sample));
  }

  @Override
  public CompletableFuture<Boolean> checkVerificationCode(final String verificationCode, final byte[] sessionData) {
    try {
      final String storedVerificationCode = MessageBirdClassicSessionData.parseFrom(sessionData).getVerificationCode();
      return CompletableFuture.completedFuture(StringUtils.equals(verificationCode, storedVerificationCode));
    } catch (final InvalidProtocolBufferException e) {
      logger.error("Failed to parse stored session data", e);
      return CompletableFuture.failedFuture(e);
    }

  }

  private static Map<String, String> supportedLanguages(final List<String> original) {
    // messagebird only supports language tags that include an extension. If there's
    // a tag without an extension, we'd like it to map to one of the tags messagebird supports
    Map<String, String> ret = new HashMap<>();
    for (String lang : original) {
      ret.put(lang, lang);
      ret.putIfAbsent(lang.split("-")[0], lang);
    }
    return ret;
  }
}
