/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import jakarta.inject.Singleton;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.sender.fictitious.FictitiousNumberVerificationCodeSender;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeSender;
import org.signal.registration.sender.twilio.classic.TwilioMessagingServiceSmsSender;
import org.signal.registration.sender.twilio.classic.TwilioVoiceSender;
import org.signal.registration.sender.twilio.verify.TwilioVerifySender;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Twilio sender selection strategy chooses between different Twilio senders (but always chooses Twilio senders). It
 * will prefer Twilio Verify senders in cases where Twilio Verify supports the receiver's language preference and will
 * fall back to Programmable Messaging/Programmable Voice otherwise.
 */
@Singleton
public class TwilioSenderSelectionStrategy implements SenderSelectionStrategy {

  private final PrescribedVerificationCodeSender prescribedVerificationCodeSender;
  private final FictitiousNumberVerificationCodeSender fictitiousNumberVerificationCodeSender;
  private final TwilioVerifySender twilioVerifySender;
  private final TwilioMessagingServiceSmsSender twilioMessagingServiceSmsSender;
  private final TwilioVoiceSender twilioVoiceSender;

  private final Set<String> alwaysUseVerifyRegions;
  private final Set<String> neverUseVerifyRegions;

  public TwilioSenderSelectionStrategy(final PrescribedVerificationCodeSender prescribedVerificationCodeSender,
      final FictitiousNumberVerificationCodeSender fictitiousNumberVerificationCodeSender,
      final TwilioVerifySender twilioVerifySender,
      final TwilioMessagingServiceSmsSender twilioMessagingServiceSmsSender,
      final TwilioVoiceSender twilioVoiceSender,
      final TwilioConfiguration configuration) {

    this.prescribedVerificationCodeSender = prescribedVerificationCodeSender;
    this.fictitiousNumberVerificationCodeSender = fictitiousNumberVerificationCodeSender;
    this.twilioVerifySender = twilioVerifySender;
    this.twilioMessagingServiceSmsSender = twilioMessagingServiceSmsSender;
    this.twilioVoiceSender = twilioVoiceSender;

    alwaysUseVerifyRegions = configuration.getAlwaysUseVerifyRegions().stream()
        .map(String::toUpperCase)
        .collect(Collectors.toSet());

    neverUseVerifyRegions = configuration.getNeverUseVerifyRegions().stream()
        .map(String::toUpperCase)
        .collect(Collectors.toSet());
  }

  @Override
  public VerificationCodeSender chooseVerificationCodeSender(final MessageTransport transport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    final VerificationCodeSender sender;

    if (prescribedVerificationCodeSender.supportsDestination(transport, phoneNumber, languageRanges, clientType)) {
      sender = prescribedVerificationCodeSender;
    } else if (fictitiousNumberVerificationCodeSender.supportsDestination(transport, phoneNumber, languageRanges, clientType)) {
      sender = fictitiousNumberVerificationCodeSender;
    } else {
      final String region = PhoneNumberUtil.getInstance().getRegionCodeForNumber(phoneNumber).toUpperCase();
      final boolean useTwilioVerify;

      if (alwaysUseVerifyRegions.contains(region)) {
        useTwilioVerify = true;
      } else if (neverUseVerifyRegions.contains(region)) {
        useTwilioVerify = false;
      } else {
        useTwilioVerify = twilioVerifySender.supportsDestination(transport, phoneNumber, languageRanges, clientType);
      }

      sender = useTwilioVerify ? twilioVerifySender :
          switch (transport) {
            case SMS -> twilioMessagingServiceSmsSender;
            case VOICE -> twilioVoiceSender;
          };
    }

    return sender;
  }
}
