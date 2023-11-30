/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.infobip;

import com.infobip.ApiClient;
import com.infobip.ApiKey;
import com.infobip.BaseUrl;
import com.infobip.api.SmsApi;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

@Factory
public class InfobipClientFactory {
  @Singleton
  ApiClient infobipClient(final InfobipClientConfiguration configuration) {
    return ApiClient.forApiKey(ApiKey.from(configuration.apiKey()))
        .withBaseUrl(BaseUrl.from(configuration.baseUrl()))
        .build();
  }

  @Singleton
  SmsApi smsApiClient(final InfobipClientConfiguration configuration) {
    return new SmsApi(infobipClient(configuration));
  }
}
