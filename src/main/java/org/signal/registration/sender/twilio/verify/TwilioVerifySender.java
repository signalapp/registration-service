/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.verify;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.InvalidProtocolBufferException;
import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import com.twilio.rest.verify.v2.service.VerificationCreator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.AttemptData;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderInvalidParametersException;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.UnsupportedMessageTransportException;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.sender.twilio.TwilioExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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


  private static final String INVALID_PARAM_NAME = MetricsUtil.name(TwilioVerifySender.class, "invalidParam");

  private static final Map<MessageTransport, Verification.Channel> CHANNELS_BY_TRANSPORT = new EnumMap<>(Map.of(
      MessageTransport.SMS, Verification.Channel.SMS,
      MessageTransport.VOICE, Verification.Channel.CALL
  ));

  TwilioVerifySender(
      final MeterRegistry meterRegistry,
      final TwilioRestClient twilioRestClient,
      final TwilioVerifyConfiguration configuration,
      final ApiClientInstrumenter apiClientInstrumenter) {
    this.meterRegistry = meterRegistry;
    this.twilioRestClient = twilioRestClient;
    this.configuration = configuration;
    this.apiClientInstrumenter = apiClientInstrumenter;
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
    return
        (messageTransport == MessageTransport.SMS
            && StringUtils.isNotBlank(configuration.customTemplateSid())
            && Locale.lookupTag(languageRanges, configuration.customTemplateSupportedLanguages()) != null)
        || Locale.lookupTag(languageRanges, configuration.supportedLanguages()) != null;
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
  public AttemptData sendVerificationCode(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) throws SenderRejectedRequestException {

    final Verification.Channel channel = CHANNELS_BY_TRANSPORT.get(messageTransport);

    if (channel == null) {
      throw new UnsupportedMessageTransportException();
    }

    final VerificationCreator verificationCreator =
        Verification.creator(configuration.serviceSid(),
                PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164),
                channel.toString())
            .setCustomFriendlyName(configuration.serviceFriendlyName());

    final String locale;
    if (messageTransport == MessageTransport.SMS && StringUtils.isNotBlank(configuration.customTemplateSid())) {
      final String customTemplateLocale = Locale.lookupTag(languageRanges, configuration.customTemplateSupportedLanguages());
      if (customTemplateLocale != null) {
        locale = customTemplateLocale;
        verificationCreator.setTemplateSid(configuration.customTemplateSid());
      } else {
        locale = Locale.lookupTag(languageRanges, configuration.supportedLanguages());
      }
    } else {
      locale = Locale.lookupTag(languageRanges, configuration.supportedLanguages());
    }
    verificationCreator.setLocale(locale);

    if (messageTransport == MessageTransport.SMS && clientType == ClientType.ANDROID_WITH_FCM) {
      verificationCreator.setAppHash(configuration.androidAppHash());
    }

    final Timer.Sample sample = Timer.start();
    final String endpointName = "verification." + messageTransport.name().toLowerCase() + ".create";

    try {
      final Verification verification = verificationCreator.create(twilioRestClient);

      final Optional<String> maybeAttemptSid = verification.getSendCodeAttempts().stream()
          .filter(attempt -> attempt.containsKey("attempt_sid"))
          .findFirst()
          .map(attempt -> attempt.get("attempt_sid").toString());

      this.apiClientInstrumenter.recordApiCallMetrics(
          this.getName(),
          endpointName,
          true,
          null,
          sample);

      return new AttemptData(maybeAttemptSid, TwilioVerifySessionData.newBuilder()
          .setVerificationSid(verification.getSid())
          .build()
          .toByteArray());
    } catch (final ApiException e) {
      this.apiClientInstrumenter.recordApiCallMetrics(
          this.getName(),
          endpointName,
          false,
          TwilioExceptions.extractErrorCode(e),
          sample);

      final Optional<SenderRejectedRequestException> maybeSenderRejectedRequestException =
          TwilioExceptions.toSenderRejectedException(e);

      maybeSenderRejectedRequestException.ifPresent(senderRejectedRequestException -> {
        if (senderRejectedRequestException instanceof SenderInvalidParametersException senderInvalidParametersException) {
          final String regionCode = PhoneNumberUtil.getInstance().getRegionCodeForNumber(phoneNumber);
          final Tags tags = senderInvalidParametersException.getParamName().map(param -> switch (param) {
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
      });

      throw maybeSenderRejectedRequestException.orElseThrow(() -> new UncheckedIOException(new IOException(e)));
    }
  }

  @Override
  public boolean checkVerificationCode(final String verificationCode, final byte[] senderData)
      throws SenderRejectedRequestException {

    try {
      final String verificationSid = TwilioVerifySessionData.parseFrom(senderData).getVerificationSid();

      final Timer.Sample sample = Timer.start();

      try {
        final VerificationCheck verificationCheck = VerificationCheck.creator(configuration.serviceSid())
            .setVerificationSid(verificationSid)
            .setCode(verificationCode)
            .create(twilioRestClient);

        apiClientInstrumenter.recordApiCallMetrics(
            this.getName(),
            "verification_check.create",
            true,
            null,
            sample);

        return verificationCheck.getValid();
      } catch (final ApiException e) {
        apiClientInstrumenter.recordApiCallMetrics(
            this.getName(),
            "verification_check.create",
            false,
            TwilioExceptions.extractErrorCode(e),
            sample);

        throw TwilioExceptions.toSenderRejectedException(e)
            .orElseThrow(() -> new UncheckedIOException(new IOException(e)));
      }
    } catch (final InvalidProtocolBufferException e) {
      logger.error("Failed to parse stored session data", e);
      throw new UncheckedIOException(e);
    }
  }
}
