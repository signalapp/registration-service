/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio;

import com.twilio.exception.ApiException;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micronaut.core.annotation.Nullable;
import org.signal.registration.metrics.MetricsUtil;

/**
 * A Twilio sender sends and (optionally) verifies verification codes via one of Twilio's APIs. This abstract base class
 * is responsible for instrumenting calls to the Twilio API.
 */
public class AbstractTwilioSender {

  private static final String CALL_COUNTER_NAME = MetricsUtil.name(AbstractTwilioSender.class, "apiCalls");

  private static final String ENDPOINT_TAG_NAME = "endpoint";
  private static final String SUCCESS_TAG_NAME = "success";
  private static final String ERROR_CODE_TAG_NAME = "code";

  protected void incrementApiCallCounter(final String endpointName, @Nullable Throwable throwable) {
    Tags tags = Tags.of(ENDPOINT_TAG_NAME, endpointName,
        SUCCESS_TAG_NAME, String.valueOf(throwable == null));

    if (throwable instanceof final ApiException apiException) {
      tags = tags.and(ERROR_CODE_TAG_NAME, String.valueOf(apiException.getCode()));
    }

    Metrics.counter(CALL_COUNTER_NAME, tags).increment();
  }
}
