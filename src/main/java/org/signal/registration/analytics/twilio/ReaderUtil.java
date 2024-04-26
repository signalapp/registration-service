/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.twilio.base.Page;
import com.twilio.base.Reader;
import com.twilio.base.Resource;
import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import java.time.Duration;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

class ReaderUtil {

  private static final int TOO_MANY_REQUESTS_CODE = 20429;

  static <T extends Resource> Flux<T> readerToFlux(final Reader<T> reader, final TwilioRestClient twilioRestClient) {
    return Flux.from(fetchPageWithBackoff(() -> reader.firstPage(twilioRestClient)))
        .expand(page -> {
          if (page.hasNextPage()) {
            return fetchPageWithBackoff(() -> reader.nextPage(page, twilioRestClient));
          } else {
            return Mono.empty();
          }
        })
        .flatMapIterable(Page::getRecords);
  }

  private static <T extends Resource> Mono<Page<T>> fetchPageWithBackoff(final Supplier<Page<T>> pageSupplier) {
    return Mono.fromSupplier(pageSupplier)
        .retryWhen(Retry.backoff(10, Duration.ofMillis(500))
            .filter(throwable -> throwable instanceof ApiException apiException && apiException.getCode() == TOO_MANY_REQUESTS_CODE)
            .maxBackoff(Duration.ofSeconds(8))
            .onRetryExhaustedThrow((backoffSpec, retrySignal) -> retrySignal.failure()));
  }
}
