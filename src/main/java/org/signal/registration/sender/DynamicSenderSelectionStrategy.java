/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.*;

import org.signal.registration.sender.fictitious.FictitiousNumberVerificationCodeSender;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeSender;
import javax.annotation.Nullable;

@Singleton
@Primary
@Requires(property = "selection")
public class DynamicSenderSelectionStrategy implements SenderSelectionStrategy {

  private final PrescribedVerificationCodeSender prescribedVerificationCodeSender;
  private final FictitiousNumberVerificationCodeSender fictitiousNumberVerificationCodeSender;
  private final Map<MessageTransport, DynamicSelector> selectorsByTransport;

  DynamicSenderSelectionStrategy(
      final List<DynamicSelector> selectors,
      final PrescribedVerificationCodeSender prescribedVerificationCodeSender,
      final FictitiousNumberVerificationCodeSender fictitiousNumberVerificationCodeSender) {

    this.prescribedVerificationCodeSender = prescribedVerificationCodeSender;
    this.fictitiousNumberVerificationCodeSender = fictitiousNumberVerificationCodeSender;
    this.selectorsByTransport = new EnumMap<>(MessageTransport.class);
    for (DynamicSelector s : selectors) {
      this.selectorsByTransport.put(s.getTransport(), s);
    }
    if (!Arrays.stream(MessageTransport.values()).allMatch(selectorsByTransport::containsKey)) {
      throw new IllegalArgumentException("Invalid configuration: missing transport types");
    }
  }

  @Override
  public SenderSelection chooseVerificationCodeSender(final MessageTransport transport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType,
      final @Nullable String preferredSender,
      final Set<String> previouslyFailedSenders) {

    if (prescribedVerificationCodeSender.supportsLanguage(transport, phoneNumber, languageRanges)) {
      return new SenderSelection(prescribedVerificationCodeSender, SelectionReason.CONFIGURED);
    } else if (fictitiousNumberVerificationCodeSender.supportsLanguage(transport, phoneNumber, languageRanges)) {
      return new SenderSelection(fictitiousNumberVerificationCodeSender, SelectionReason.CONFIGURED);
    } else {
      return this.selectorsByTransport
          .get(transport)
          .chooseVerificationCodeSender(phoneNumber, languageRanges, clientType, preferredSender, previouslyFailedSenders);
    }
  }

}
