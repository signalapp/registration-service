/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.messagebird.verify;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.InvalidProtocolBufferException;
import com.messagebird.MessageBirdClient;
import com.messagebird.exceptions.MessageBirdException;
import com.messagebird.objects.DataCodingType;
import com.messagebird.objects.Language;
import com.messagebird.objects.Verify;
import com.messagebird.objects.VerifyRequest;
import com.messagebird.objects.VerifyType;
import io.micrometer.core.instrument.Metrics;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.UnsupportedMessageTransportException;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.sender.VerificationSmsBodyProvider;
import org.signal.registration.sender.messagebird.MessageBirdErrorCodeExtractor;
import org.signal.registration.sender.messagebird.SenderIdSelector;
import org.signal.registration.sender.messagebird.MessageBirdVerifySessionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies numbers via the MessageBird Verify API. The <a
 * href="https://developers.messagebird.com/api/verify/#verify-api">MessageBird Verify API</a> verifies numbers via a
 * MessageBird generated code. When the user supplies a verification code, it must be passed back to MessageBird for
 * verification.
 */
@Singleton
public class MessageBirdVerifySender implements VerificationCodeSender {

  private static final Logger logger = LoggerFactory.getLogger(MessageBirdVerifySender.class);

  private static final Map<String, Language> SUPPORTED_VOICE_LANGUAGES = supportedVoiceLanguages();
  private final MessageBirdClient client;
  private final MessageBirdVerifyConfiguration configuration;
  private final Executor executor;
  private final VerificationSmsBodyProvider verificationSmsBodyProvider;
  private final ApiClientInstrumenter apiClientInstrumenter;
  private final SenderIdSelector senderIdSelector;
  private static final String VERIFY_COUNTER_NAME = MetricsUtil.name(MessageBirdVerifySender.class, "verify");


  public MessageBirdVerifySender(
      final MessageBirdVerifyConfiguration configuration,
      final @Named(TaskExecutors.IO) Executor executor,
      final MessageBirdClient messageBirdClient,
      final VerificationSmsBodyProvider verificationSmsBodyProvider,
      final ApiClientInstrumenter apiClientInstrumenter,
      final SenderIdSelector senderIdSelector) {
    this.configuration = configuration;
    this.executor = executor;
    this.client = messageBirdClient;
    this.apiClientInstrumenter = apiClientInstrumenter;
    this.verificationSmsBodyProvider = verificationSmsBodyProvider;
    this.senderIdSelector = senderIdSelector;
  }

  @Override
  public String getName() {
    return "messagebird-verify";
  }

  @Override
  public Duration getSessionTtl() {
    return this.configuration.sessionTtl();
  }

  @Override
  public boolean supportsDestination(final MessageTransport messageTransport, final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges, final ClientType clientType) {
    return switch (messageTransport) {
      case SMS -> Locale.lookupTag(languageRanges, verificationSmsBodyProvider.getSupportedLanguages()) != null;
      case VOICE -> lookupMessageBirdLanguage(languageRanges).isPresent();
    };
  }

  @Override
  public CompletableFuture<byte[]> sendVerificationCode(
      final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) throws UnsupportedMessageTransportException {
    final String e164 = PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
    final VerifyRequest request = new VerifyRequest(e164);
    request.setType(verifyType(messageTransport));

    request.setDatacoding(DataCodingType.auto);
    request.setOriginator(this.senderIdSelector.getSenderId(phoneNumber));
    request.setTimeout((int) this.configuration.sessionTtl().toSeconds());

    switch (messageTransport) {
      case VOICE:
        // for voice, MessageBird verify does translation as long as the language is set
        lookupMessageBirdLanguage(languageRanges).ifPresent(request::setLanguage);
        break;
      case SMS:
        request.setTemplate(verificationSmsBodyProvider.getVerificationSmsBody(
            phoneNumber,
            clientType,
            // our "code" is `%token` which messagebird will replace with an actual code
            "%token",
            languageRanges));
        break;
    }

    final String endpointName = "verification." + messageTransport.name().toLowerCase() + ".create";
    return CompletableFuture
        .supplyAsync(() -> {
          try {
            final Verify verify = this.client.sendVerifyToken(request);
            return switch (CreateVerifyStatus.fromName(verify.getStatus())) {
              case SENT -> MessageBirdVerifySessionData
                  .newBuilder()
                  .setVerificationId(verify.getId())
                  .build()
                  .toByteArray();
              case FAILED -> throw new CompletionException(new IOException("Failed to send"));
            };
          } catch (MessageBirdException e) {
            throw new CompletionException(e);
          }
        }, this.executor)
        .whenComplete((ignored, throwable) ->
            this.apiClientInstrumenter.incrementCounter(
                this.getName(),
                endpointName,
                throwable == null,
                MessageBirdErrorCodeExtractor.extract(throwable)));
  }

  @Override
  public CompletableFuture<Boolean> checkVerificationCode(final String verificationCode, final byte[] sessionData) {
    final String verificationId;
    try {
      verificationId = MessageBirdVerifySessionData.parseFrom(sessionData).getVerificationId();
    } catch (final InvalidProtocolBufferException e) {
      logger.error("Failed to parse stored session data", e);
      return CompletableFuture.failedFuture(e);
    }

    return CompletableFuture
        .supplyAsync(() -> {
          try {
            Verify verify = this.client.verifyToken(verificationId, verificationCode);
            Metrics.counter(VERIFY_COUNTER_NAME, "outcome", verify.getStatus()).increment();
            return VerifyStatus.fromName(verify.getStatus()) == VerifyStatus.VERIFIED;
          } catch (MessageBirdException e) {
            throw new CompletionException(e);
          }
        }, this.executor)
        .whenComplete((ignored, throwable) ->
            this.apiClientInstrumenter.incrementCounter(
                this.getName(),
                "verification_check.create",
                throwable == null,
                MessageBirdErrorCodeExtractor.extract(throwable)));
  }

  @VisibleForTesting
  Optional<Language> lookupMessageBirdLanguage(final List<Locale.LanguageRange> languageRanges) {
    return Optional
        .ofNullable(Locale.lookupTag(languageRanges, SUPPORTED_VOICE_LANGUAGES.keySet()))
        .map(SUPPORTED_VOICE_LANGUAGES::get);
  }

  private static VerifyType verifyType(MessageTransport messageTransport) {
    return switch (messageTransport) {
      case SMS -> VerifyType.SMS;
      case VOICE -> VerifyType.TTS;
    };
  }


  /*
   * Possible values: sent, expired, failed, verified, and deleted
   * From <a href="https://developers.messagebird.com/api/verify/"> Message bird API docs </a>
   */

  enum CreateVerifyStatus {
    SENT("sent"),
    FAILED("failed");

    private static final Map<String, CreateVerifyStatus> MAP = Arrays
        .stream(CreateVerifyStatus.values())
        .collect(Collectors.toMap(CreateVerifyStatus::getName, Function.identity()));

    private final String name;

    CreateVerifyStatus(String name) {
      this.name = name;
    }

    public String getName() {
      return this.name;
    }

    public static CreateVerifyStatus fromName(String name) throws IllegalArgumentException {
      if (name == null || !MAP.containsKey(name)) {
        logger.warn("Invalid create status {} from messagebird", name);
        throw new IllegalArgumentException("Invalid create status " + name);
      }
      return MAP.get(name);
    }
  }

  enum VerifyStatus {
    EXPIRED("expired"),
    FAILED("failed"),
    VERIFIED("verified"),
    DELETED("deleted");

    private static final Map<String, VerifyStatus> MAP = Arrays
        .stream(VerifyStatus.values())
        .collect(Collectors.toMap(VerifyStatus::getName, Function.identity()));

    private final String name;

    VerifyStatus(String name) {
      this.name = name;
    }

    public String getName() {
      return this.name;
    }

    public static VerifyStatus fromName(String name) {
      if (name == null || !MAP.containsKey(name)) {
        logger.warn("Invalid verify status {} from messagebird", name);
        throw new IllegalArgumentException("Invalid verify status " + name);
      }
      return MAP.get(name);
    }
  }

  private static Map<String, Language> supportedVoiceLanguages() {
    final Map<String, Language> mbTags = Arrays
        .stream(Language.values())
        .collect(Collectors.toMap(Language::getCode, Function.identity()));

    // map of less specific language tags (ex. "en") to an extlang specific equivalent (ex "en-us")
    final Map<String, Language> equivalents = Arrays.stream(Language.values())
        .collect(Collectors.toMap(
            lang -> lang.getCode().split("-")[0],
            Function.identity(),
            // just use the first tag in enum order
            (a, b) -> a
        ));
    mbTags.putAll(equivalents);
    return mbTags;
  }
}
