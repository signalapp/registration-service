/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.signal.registration.metrics.MetricsUtil;

@Singleton
public class ApiClientInstrumenter {

  private static final String CALL_COUNTER_NAME = MetricsUtil.name(ApiClientInstrumenter.class, "apiCalls");

  private static final String ENDPOINT_TAG_NAME = "endpoint";
  private static final String PROVIDER_TAG_NAME = "provider";
  private static final String SUCCESS_TAG_NAME = "success";
  private static final String ERROR_CODE_TAG_NAME = "code";
  private MeterRegistry meterRegistry;

  public ApiClientInstrumenter(final MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void incrementCounter(
      final String providerName,
      final String endpointName,
      final boolean success,
      @Nullable String errorCode) {
    final List<Tag> tags = new ArrayList<>(4);
    tags.add(Tag.of(ENDPOINT_TAG_NAME, endpointName));
    tags.add(Tag.of(PROVIDER_TAG_NAME, providerName));
    tags.add(Tag.of(SUCCESS_TAG_NAME, String.valueOf(success)));
    Optional
        .ofNullable(errorCode)
        .ifPresent(s -> tags.add(Tag.of(ERROR_CODE_TAG_NAME, s)));

    meterRegistry.counter(CALL_COUNTER_NAME, tags).increment();
  }

}
