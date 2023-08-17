/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.messagebird;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.client.HttpClient;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.signal.registration.analytics.Money;
import reactor.core.publisher.Mono;

class MessageBirdSmsPriceProviderTest {

  private static final Currency EUR = Currency.getInstance("EUR");

  // Via https://developers.messagebird.com/quickstarts/pricingapi/list-outbound-sms-prices/
  private static final String RESPONSE_JSON = """
      {
          "gateway": 10,
          "currencyCode": "EUR",
          "totalCount": 3,
          "prices": [
              {
                  "price": "0.060000",
                  "currencyCode": "EUR",
                  "mccmnc": "0",
                  "mcc": "0",
                  "mnc": null,
                  "countryName": "Default Rate",
                  "countryIsoCode": "XX",
                  "operatorName": "Default Rate"
              },
              {
                  "price": "0.047000",
                  "currencyCode": "EUR",
                  "mccmnc": "202",
                  "mcc": "202",
                  "mnc": null,
                  "countryName": "Greece",
                  "countryIsoCode": "GR",
                  "operatorName": null
              },
              {
                  "price": "0.045000",
                  "currencyCode": "EUR",
                  "mccmnc": "20205",
                  "mcc": "202",
                  "mnc": "05",
                  "countryName": "Greece",
                  "countryIsoCode": "GR",
                  "operatorName": "Vodafone"
              }
          ]
      }
      """;

  @Test
  void getPrices() throws JsonProcessingException {
    final HttpClient httpClient = mock(HttpClient.class);
    final String accessKey = "access-key";

    when(httpClient.retrieve(any(), eq(MessageBirdSmsPriceProvider.SmsPriceResponse.class)))
        .thenReturn(Mono.just(new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readValue(RESPONSE_JSON, MessageBirdSmsPriceProvider.SmsPriceResponse.class)));

    final MessageBirdSmsPriceProvider priceProvider = new MessageBirdSmsPriceProvider(httpClient, accessKey);

    final List<MessageBirdSmsPrice> expectedPrices = List.of(
        new MessageBirdSmsPrice(null, null, null, new Money(new BigDecimal("0.060000"), EUR)),
        new MessageBirdSmsPrice("GR", "202", null, new Money(new BigDecimal("0.047000"), EUR)),
        new MessageBirdSmsPrice("GR", "202", "05", new Money(new BigDecimal("0.045000"), EUR)));

    assertEquals(expectedPrices, priceProvider.getPrices().collectList().block());
  }
}
