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
}
