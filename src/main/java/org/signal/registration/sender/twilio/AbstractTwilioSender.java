/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio;

import com.google.common.annotations.VisibleForTesting;
import com.twilio.exception.ApiException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micronaut.core.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.util.CompletionExceptions;

/**
 * A Twilio sender sends and (optionally) verifies verification codes via one of Twilio's APIs. This abstract base class
 * is responsible for instrumenting calls to the Twilio API.
 */
public class AbstractTwilioSender {

  private final MeterRegistry meterRegistry;

  private static final String CALL_COUNTER_NAME = MetricsUtil.name(AbstractTwilioSender.class, "apiCalls");

  private static final String ENDPOINT_TAG_NAME = "endpoint";
  private static final String SUCCESS_TAG_NAME = "success";
  private static final String ERROR_CODE_TAG_NAME = "code";

  public AbstractTwilioSender(final MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  protected void incrementApiCallCounter(final String endpointName, @Nullable Throwable throwable) {
    final List<Tag> tags = new ArrayList<>(3);
    tags.add(Tag.of(ENDPOINT_TAG_NAME, endpointName));
    tags.add(Tag.of(SUCCESS_TAG_NAME, String.valueOf(throwable == null)));

    findCausalApiException(throwable)
        .ifPresent(apiException -> tags.add(Tag.of(ERROR_CODE_TAG_NAME, String.valueOf(apiException.getCode()))));

    meterRegistry.counter(CALL_COUNTER_NAME, tags).increment();
  }

  @VisibleForTesting
  static Optional<ApiException> findCausalApiException(final Throwable throwable) {
    return CompletionExceptions.unwrap(throwable) instanceof ApiException apiException ?
        Optional.of(apiException) : Optional.empty();
  }
}
