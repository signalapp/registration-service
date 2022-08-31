/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * A trivial verification code "sender" that never actually sends codes, but instead always uses the last six digits of
 * the destination phone number as a verification code. This sender is intended only for local testing and should never
 * be used in a production environment.
 */
@Singleton
@Requires(env = {"dev"})
public class LastDigitsOfPhoneNumberVerificationCodeSender implements VerificationCodeSender {

  @Override
  public MessageTransport getTransport() {
    return MessageTransport.SMS;
  }

  @Override
  public Duration getSessionTtl() {
    return Duration.ofMinutes(10);
  }

  @Override
  public boolean supportsDestination(final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    return true;
  }

  @Override
  public CompletableFuture<byte[]> sendVerificationCode(final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    final String e164String = PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
    final String verificationCode = e164String.substring(e164String.length() - 6);

    return CompletableFuture.completedFuture(verificationCode.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public CompletableFuture<Boolean> checkVerificationCode(final String verificationCode, final byte[] sessionData) {
    return CompletableFuture.completedFuture(verificationCode.equals(new String(sessionData, StandardCharsets.UTF_8)));
  }
}
