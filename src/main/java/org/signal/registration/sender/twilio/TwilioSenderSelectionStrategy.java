/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio;

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

  public TwilioSenderSelectionStrategy(final PrescribedVerificationCodeSender prescribedVerificationCodeSender,
      final FictitiousNumberVerificationCodeSender fictitiousNumberVerificationCodeSender,
      final TwilioVerifySender twilioVerifySender,
      final TwilioMessagingServiceSmsSender twilioMessagingServiceSmsSender,
      final TwilioVoiceSender twilioVoiceSender) {

    this.prescribedVerificationCodeSender = prescribedVerificationCodeSender;
    this.fictitiousNumberVerificationCodeSender = fictitiousNumberVerificationCodeSender;
    this.twilioVerifySender = twilioVerifySender;
    this.twilioMessagingServiceSmsSender = twilioMessagingServiceSmsSender;
    this.twilioVoiceSender = twilioVoiceSender;
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
    } else if (twilioVerifySender.supportsDestination(transport, phoneNumber, languageRanges, clientType)) {
      sender = twilioVerifySender;
    } else {
      sender = switch (transport) {
        case SMS -> twilioMessagingServiceSmsSender;
        case VOICE -> twilioVoiceSender;
      };
    }

    return sender;
  }
}
