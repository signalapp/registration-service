/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.tuple.Pair;
import org.signal.registration.Environments;
import java.time.Clock;

@Factory
@Requires(env = Environments.DEVELOPMENT)
class AllowAllRateLimiterFactory {

  private final Clock clock;

  AllowAllRateLimiterFactory(final Clock clock) {
    this.clock = clock;
  }

  @Singleton
  @Named("session-creation")
  RateLimiter<Pair<Phonenumber.PhoneNumber, String>> sessionCreationRateLimiter() {
    return new AllowAllRateLimiter<>(clock);
  }

  @Singleton
  @Named("send-sms-verification-code-per-session")
  RateLimiter<Phonenumber.PhoneNumber> sendSmsVerificationCodePerSessionRateLimiter() {
    return new AllowAllRateLimiter<>(clock);
  }

  @Singleton
  @Named("send-voice-verification-code-per-session")
  RateLimiter<Phonenumber.PhoneNumber> sendVoiceVerificationCodePerSessionRateLimiter() {
    return new AllowAllRateLimiter<>(clock);
  }

  @Singleton
  @Named("check-verification-code-per-session")
  RateLimiter<Phonenumber.PhoneNumber> checkVerificationCodePerSessionRateLimiter() {
    return new AllowAllRateLimiter<>(clock);
  }

  @Singleton
  @Named("send-sms-verification-code-per-number")
  RateLimiter<Phonenumber.PhoneNumber> sendSmsVerificationCodePerNumberRateLimiter() {
    return new AllowAllRateLimiter<>(clock);
  }

  @Singleton
  @Named("send-voice-verification-code-per-number")
  RateLimiter<Phonenumber.PhoneNumber> sendVoiceVerificationCodePerNumberRateLimiter() {
    return new AllowAllRateLimiter<>(clock);
  }

  @Singleton
  @Named("check-verification-code-per-number")
  RateLimiter<Phonenumber.PhoneNumber> checkVerificationCodePerNumberRateLimiter() {
    return new AllowAllRateLimiter<>(clock);
  }
}
