/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.signal.registration.sender.fictitious.FictitiousNumberVerificationCodeSender;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeSender;

@Singleton
@Primary
@Requires(property = "selection")
public class WeightedSenderSelectionStrategy implements SenderSelectionStrategy {

  private final PrescribedVerificationCodeSender prescribedVerificationCodeSender;
  private final FictitiousNumberVerificationCodeSender fictitiousNumberVerificationCodeSender;
  private final Map<MessageTransport, WeightedSelector> selectorsByTransport;

  WeightedSenderSelectionStrategy(
      final List<WeightedSelector> selectors,
      final PrescribedVerificationCodeSender prescribedVerificationCodeSender,
      final FictitiousNumberVerificationCodeSender fictitiousNumberVerificationCodeSender) {

    this.prescribedVerificationCodeSender = prescribedVerificationCodeSender;
    this.fictitiousNumberVerificationCodeSender = fictitiousNumberVerificationCodeSender;
    this.selectorsByTransport = new EnumMap(MessageTransport.class);
    for (WeightedSelector s : selectors) {
      this.selectorsByTransport.put(s.getTransport(), s);
    }
    if (!Arrays.stream(MessageTransport.values()).allMatch(selectorsByTransport::containsKey)) {
      throw new IllegalArgumentException("Invalid configuration: missing transport types");
    }
  }

  @Override
  public VerificationCodeSender chooseVerificationCodeSender(final MessageTransport transport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    final VerificationCodeSender sender;

    if (prescribedVerificationCodeSender.supportsDestination(transport, phoneNumber, languageRanges, clientType)) {
      sender = prescribedVerificationCodeSender;
    } else if (fictitiousNumberVerificationCodeSender.supportsDestination(transport, phoneNumber, languageRanges,
        clientType)) {
      sender = fictitiousNumberVerificationCodeSender;
    } else {
      sender = this.selectorsByTransport.get(transport)
          .chooseVerificationCodeSender(phoneNumber, languageRanges, clientType);
    }
    return sender;
  }

}
