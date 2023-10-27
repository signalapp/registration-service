/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender;

import com.google.i18n.phonenumbers.Phonenumber;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A sender selection strategy chooses a {@link VerificationCodeSender} for the given message transport, destination
 * phone number, language preferences, and client type.
 */
public interface SenderSelectionStrategy {


  /**
   * A selected VerificationCodeSender
   *
   * @param sender The @{@link VerificationCodeSender} to use
   * @param reason Why the sender was chosen
   */
  record SenderSelection(VerificationCodeSender sender, SelectionReason reason) {}

  /**
   * Selects a verification code sender for the given message transport, destination phone number, language preferences,
   * and client type.
   *
   * @param transport               the message transport via which to send a verification code
   * @param phoneNumber             the phone number to which to send a verification code
   * @param languageRanges          a prioritized list of language preferences for the receiver of the verification code
   * @param clientType              the type of client receiving the verification code
   * @param preferredSender         if provided, a sender to use
   * @param previouslyFailedSenders senders that have previously been used in this verification session that have not
   *                                produced a successful verification
   * @return a {@link SenderSelection} appropriate for the given message transport, phone number, language preferences,
   * and client type
   */
  SenderSelection chooseVerificationCodeSender(MessageTransport transport,
      Phonenumber.PhoneNumber phoneNumber,
      List<Locale.LanguageRange> languageRanges,
      ClientType clientType,
      @Nullable String preferredSender,
      Set<String> previouslyFailedSenders);

  enum SelectionReason {

    /**
     * Sender selected at random
     */
    RANDOM("random"),

    /**
     * A configuration override explicitly indicated this sender
     */
    CONFIGURED("configured"),

    /**
     * Sender selected by the adaptive routing strategy
     */
    ADAPTIVE("adaptive"),

    /**
     * Sender selected because it supports the requested language
     */
    LANGUAGE_SUPPORT("language_support"),

    /**
     * The sender was specifically requested
     */
    PREFERRED("preferred"),

    UNKNOWN("unknown");

    final String reason;

    SelectionReason(String reason) {
      this.reason = reason;
    }

    @Override
    public String toString() {
      return this.reason;
    }
  }
}
