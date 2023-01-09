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

@Factory
// Note: we're temporarily allowing all actions during the transition period where rate limiting is happening on the
// caller's end; allow-all limiters will be restricted to the `dev` environment and replaced with "real" limiters
// shortly.
// @Requires(env = "dev")
class AllowAllRateLimiterFactory {

  @Singleton
  @Named("session-creation")
  RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter() {
    return new AllowAllRateLimiter<>();
  }
}
