/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.verify;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.InvalidProtocolBufferException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import com.twilio.rest.verify.v2.service.VerificationCheckCreator;
import com.twilio.rest.verify.v2.service.VerificationCreator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.AttemptData;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderInvalidParametersException;
import org.signal.registration.sender.UnsupportedMessageTransportException;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.sender.twilio.ApiExceptions;
import org.signal.registration.util.CompletionExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * A Twilio Verify sender sends verification codes to end users via Twilio Verify.
 */
@Singleton
public class TwilioVerifySender implements VerificationCodeSender {

  private static final Logger logger = LoggerFactory.getLogger(TwilioVerifySender.class);

  public static final String SENDER_NAME = "twilio-verify";

  private final MeterRegistry meterRegistry;
  private final TwilioRestClient twilioRestClient;
  private final TwilioVerifyConfiguration configuration;
  private final ApiClientInstrumenter apiClientInstrumenter;
  private final Duration minRetryWait;
  private final int maxRetries;


  private static final String INVALID_PARAM_NAME = MetricsUtil.name(TwilioVerifySender.class, "invalidParam");

  private static final Map<MessageTransport, Verification.Channel> CHANNELS_BY_TRANSPORT = new EnumMap<>(Map.of(
      MessageTransport.SMS, Verification.Channel.SMS,
      MessageTransport.VOICE, Verification.Channel.CALL
  ));

  TwilioVerifySender(
      final MeterRegistry meterRegistry,
      final TwilioRestClient twilioRestClient,
      final TwilioVerifyConfiguration configuration,
      final ApiClientInstrumenter apiClientInstrumenter,
      final @Value("${twilio.min-retry-wait:100ms}") Duration minRetryWait,
      final @Value("${twilio.max-retries:5}") int maxRetries) {
    this.meterRegistry = meterRegistry;
    this.twilioRestClient = twilioRestClient;
    this.configuration = configuration;
    this.apiClientInstrumenter = apiClientInstrumenter;
    this.minRetryWait = minRetryWait;
    this.maxRetries = maxRetries;
  }

  @Override
  public boolean supportsTransport(final MessageTransport transport) {
    return transport == MessageTransport.SMS || transport == MessageTransport.VOICE;
  }

  @Override
  public boolean supportsLanguage(
      final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges) {
    return Locale.lookupTag(languageRanges, configuration.supportedLanguages()) != null;
  }

  @Override
  public String getName() {
    return SENDER_NAME;
  }

  @Override
  public Duration getAttemptTtl() {
    // Upstream sessions time out after ten minutes; see
    // https://support.twilio.com/hc/en-us/articles/360033354913-What-is-the-Default-Verify-V2-Expiration-Time-
    return Duration.ofMinutes(10);
  }

  @Override
  public CompletableFuture<AttemptData> sendVerificationCode(final MessageTransport messageTransport,
                                                             final Phonenumber.PhoneNumber phoneNumber,
                                                             final List<Locale.LanguageRange> languageRanges,
                                                             final ClientType clientType) throws UnsupportedMessageTransportException {

    final Verification.Channel channel = CHANNELS_BY_TRANSPORT.get(messageTransport);

    if (channel == null) {
      throw new UnsupportedMessageTransportException();
    }

    final VerificationCreator verificationCreator =
        Verification.creator(configuration.serviceSid(),
                PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164),
                channel.toString())
            .setCustomFriendlyName(configuration.serviceFriendlyName());

    List<String> supportedLangauges = configuration.supportedLanguages();
    if (messageTransport == MessageTransport.SMS && StringUtils.isNotBlank(configuration.customTemplateSid())) {
      supportedLangauges = configuration.customTemplateSupportedLanguages();
      verificationCreator.setTemplateSid(configuration.customTemplateSid());
    }
    final String locale = Locale.lookupTag(languageRanges, supportedLangauges);
    verificationCreator.setLocale(locale);

    if (clientType == ClientType.ANDROID_WITH_FCM) {
      verificationCreator.setAppHash(configuration.androidAppHash());
    }

    final Timer.Sample sample = Timer.start();
    final String endpointName = "verification." + messageTransport.name().toLowerCase() + ".create";

    return withRetries(() -> verificationCreator.createAsync(twilioRestClient), endpointName)
        .toCompletableFuture()
        .whenComplete((sessionData, throwable) ->
            this.apiClientInstrumenter.recordApiCallMetrics(
                this.getName(),
                endpointName,
                throwable == null,
                ApiExceptions.extractErrorCode(throwable),
                sample)
        )
        .handle((verification, throwable) -> {
          if (throwable == null) {
            final Optional<String> maybeAttemptSid = verification.getSendCodeAttempts().stream()
                .filter(attempt -> attempt.containsKey("attempt_sid"))
                .findFirst()
                .map(attempt -> attempt.get("attempt_sid").toString());

            return new AttemptData(maybeAttemptSid, TwilioVerifySessionData.newBuilder()
                .setVerificationSid(verification.getSid())
                .build()
                .toByteArray());
          }

          final Throwable exception = ApiExceptions.toSenderException(throwable);
          if (exception instanceof SenderInvalidParametersException p) {
            final String regionCode = PhoneNumberUtil.getInstance().getRegionCodeForNumber(phoneNumber);
            final Tags tags = p.getParamName().map(param -> switch (param) {
                  case NUMBER -> Tags.of("paramType", "number",
                      MetricsUtil.TRANSPORT_TAG_NAME, messageTransport.name(),
                      MetricsUtil.REGION_CODE_TAG_NAME, StringUtils.defaultIfBlank(regionCode, "XX"));
                  case LOCALE -> Tags.of("paramType", "locale",
                      "locale", locale,
                      MetricsUtil.TRANSPORT_TAG_NAME, messageTransport.name());
                })
                .orElse(Tags.of("paramType", "unknown"));
            meterRegistry.counter(INVALID_PARAM_NAME, tags).increment();
          }
          throw CompletionExceptions.wrap(exception);
        });
  }

  @Override
  public CompletableFuture<Boolean> checkVerificationCode(final String verificationCode, final byte[] senderData) {
    try {
      final String verificationSid = TwilioVerifySessionData.parseFrom(senderData).getVerificationSid();

      final Timer.Sample sample = Timer.start();

      final VerificationCheckCreator creator = VerificationCheck.creator(
              configuration.serviceSid())
          .setVerificationSid(verificationSid)
          .setCode(verificationCode);
      return withRetries(() -> creator.createAsync(twilioRestClient), "verification_check.create")
          .thenApply(VerificationCheck::getValid)
          .handle((verificationCheck, throwable) -> {
            if (throwable == null) {
              return verificationCheck;
            }

            throw CompletionExceptions.wrap(ApiExceptions.toSenderException(throwable));
          })
          .whenComplete((verificationCheck, throwable) ->
              apiClientInstrumenter.recordApiCallMetrics(
                  this.getName(),
                  "verification_check.create",
                  throwable == null,
                  ApiExceptions.extractErrorCode(throwable),
                  sample))
          .toCompletableFuture();
    } catch (final InvalidProtocolBufferException e) {
      logger.error("Failed to parse stored session data", e);
      return CompletableFuture.failedFuture(e);
    }
  }

  <T> CompletionStage<T> withRetries(
      final Supplier<CompletionStage<T>> supp,
      final String endpointName) {
    return Mono.defer(() -> Mono.fromCompletionStage(supp))
        .retryWhen(Retry
            .backoff(maxRetries, minRetryWait)
            .filter(ApiExceptions::isRetriable)
            .doBeforeRetry(retrySignal ->
              this.apiClientInstrumenter.recordApiRetry(
                  this.getName(),
                  endpointName,
                  ApiExceptions.extractErrorCode(retrySignal.failure()))))
        .onErrorMap(Exceptions::isRetryExhausted, Throwable::getCause)
        .toFuture();
  }
}
