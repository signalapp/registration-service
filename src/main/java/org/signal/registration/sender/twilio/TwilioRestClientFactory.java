/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio;

import com.twilio.http.TwilioRestClient;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

@Factory
class TwilioRestClientFactory {

  @Singleton
  TwilioRestClient twilioRestClient(final TwilioConfiguration configuration) {
    return new TwilioRestClient.Builder(configuration.getApiKeySid(), configuration.getApiKeySecret())
        .accountSid(configuration.getAccountSid())
        .build();
  }
}
