/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.messagebird;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.analytics.Money;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
class MessageBirdSmsPriceProvider {

  private final HttpClient httpClient;
  private final String accessKey;

  private static final String DEFAULT_RATE_COUNTRY_CODE = "XX";

  MessageBirdSmsPriceProvider(@Client("https://rest.messagebird.com/") final HttpClient httpClient,
      @Value("${messagebird.access-key}") final String accessKey) {

    this.httpClient = httpClient;
    this.accessKey = accessKey;
  }

  record SmsPriceEntry(BigDecimal price, String currencyCode, String mcc, String mnc, String countryIsoCode) {
  }

  record SmsPriceResponse(List<SmsPriceEntry> prices) {
  }

  Flux<MessageBirdSmsPrice> getPrices() {
    return Mono.from(httpClient.retrieve(HttpRequest.GET("/pricing/sms/outbound")
                .header("Authorization", "AccessKey " + accessKey),
            SmsPriceResponse.class))
        .flatMapMany(response -> Flux.fromIterable(response.prices())
            .map(entry -> {
              final Money price = new Money(entry.price(), Currency.getInstance(entry.currencyCode()));

              if (DEFAULT_RATE_COUNTRY_CODE.equals(entry.countryIsoCode()) && entry.mnc() == null) {
                return new MessageBirdSmsPrice(null, null, null, price);
              }

              return new MessageBirdSmsPrice(entry.countryIsoCode(), entry.mcc(), entry.mnc(), price);
            }));
  }
}
