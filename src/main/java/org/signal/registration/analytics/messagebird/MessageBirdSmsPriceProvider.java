/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.messagebird;

import com.messagebird.MessageBirdClient;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.Currency;
import java.util.concurrent.Executor;
import org.signal.registration.analytics.Money;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Singleton
class MessageBirdSmsPriceProvider {

  private final MessageBirdClient messageBirdClient;
  private final Executor executor;

  private static final String DEFAULT_RATE_COUNTRY_CODE = "XX";

  MessageBirdSmsPriceProvider(final MessageBirdClient messageBirdClient,
      @Named(TaskExecutors.IO) final Executor executor) {

    this.messageBirdClient = messageBirdClient;
    this.executor = executor;
  }

  Flux<MessageBirdSmsPrice> getPrices() {
    return Mono.fromCallable(messageBirdClient::getOutboundSmsPrices)
        .subscribeOn(Schedulers.fromExecutor(executor))
        .flatMapMany(outboundSmsPriceResponse -> Flux.fromIterable(outboundSmsPriceResponse.getPrices())
            .map(entry -> {
              final Money price = new Money(entry.getPrice(), Currency.getInstance(entry.getCurrencyCode()));

              if (DEFAULT_RATE_COUNTRY_CODE.equals(entry.getCountryIsoCode()) && entry.getMnc() == null) {
                return new MessageBirdSmsPrice(null, null, null, price);
              }

              return new MessageBirdSmsPrice(entry.getCountryIsoCode(), entry.getMcc(), entry.getMnc(), price);
            }));
  }
}
