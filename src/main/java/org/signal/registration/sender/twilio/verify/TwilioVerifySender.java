/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.verify;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.InvalidProtocolBufferException;
import com.twilio.rest.verify.v2.service.Verification;
import com.twilio.rest.verify.v2.service.VerificationCheck;
import com.twilio.rest.verify.v2.service.VerificationCreator;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import jakarta.inject.Singleton;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.UnsupportedMessageTransportException;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.sender.twilio.AbstractTwilioSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Twilio Verify sender sends verification codes to end users via Twilio Verify.
 */
@Singleton
public class TwilioVerifySender extends AbstractTwilioSender implements VerificationCodeSender {

  private final TwilioVerifyConfiguration configuration;

  private static final Map<MessageTransport, Verification.Channel> CHANNELS_BY_TRANSPORT = new EnumMap<>(Map.of(
      MessageTransport.SMS, Verification.Channel.SMS,
      MessageTransport.VOICE, Verification.Channel.CALL
  ));

  private static final Logger logger = LoggerFactory.getLogger(TwilioVerifySender.class);

  protected TwilioVerifySender(final TwilioVerifyConfiguration configuration) {
    this.configuration = configuration;
  }

  @Override
  public boolean supportsDestination(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    return CHANNELS_BY_TRANSPORT.containsKey(messageTransport) &&
        Locale.lookupTag(languageRanges, configuration.getSupportedLanguages()) != null;
  }

  @Override
  public Duration getSessionTtl() {
    // Upstream sessions time out after ten minutes; see
    // https://support.twilio.com/hc/en-us/articles/360033354913-What-is-the-Default-Verify-V2-Expiration-Time-
    return Duration.ofMinutes(10);
  }

  @Override
  public CompletableFuture<byte[]> sendVerificationCode(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) throws UnsupportedMessageTransportException {

    final Verification.Channel channel = CHANNELS_BY_TRANSPORT.get(messageTransport);

    if (channel == null) {
      throw new UnsupportedMessageTransportException();
    }

    final VerificationCreator verificationCreator =
        Verification.creator(configuration.getServiceSid(),
                PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164),
                channel.toString())
            .setCustomFriendlyName(configuration.getServiceFriendlyName())
            .setLocale(Locale.lookupTag(languageRanges, configuration.getSupportedLanguages()));

    if (clientType == ClientType.ANDROID_WITH_FCM) {
      verificationCreator.setAppHash(configuration.getAndroidAppHash());
    }

    return verificationCreator.createAsync()
        .thenApply(verification -> TwilioVerifySessionData.newBuilder()
            .setVerificationSid(verification.getSid())
            .build()
            .toByteArray())
        .whenComplete((sessionData, throwable) -> {
              final String endpointName = "verification." + messageTransport.name().toLowerCase() + ".create";
              incrementApiCallCounter(endpointName, throwable);
            });
  }

  @Override
  public CompletableFuture<Boolean> checkVerificationCode(final String verificationCode, final byte[] sessionData) {
    try {
      final String verificationSid = TwilioVerifySessionData.parseFrom(sessionData).getVerificationSid();

      return VerificationCheck.creator(configuration.getServiceSid())
          .setVerificationSid(verificationSid)
          .setCode(verificationCode)
          .createAsync()
          .thenApply(VerificationCheck::getValid)
          .whenComplete((verificationCheck, throwable) ->
              incrementApiCallCounter("verification_check.create", throwable));
    } catch (final InvalidProtocolBufferException e) {
      logger.error("Failed to parse stored session data", e);
      return CompletableFuture.failedFuture(e);
    }
  }
}
