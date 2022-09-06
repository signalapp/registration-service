/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micronaut.configuration.metrics.annotation.RequiresMetrics;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;

@Context
@RequiresMetrics
@Factory
public class GlobalMeterRegistryConfigurer {

  /**
   * Adds the primary meter registry to the global meter registry, enabling convenience methods, like
   * {@link Metrics#counter(String, String...)}, to work
   */
  GlobalMeterRegistryConfigurer(MeterRegistry meterRegistry) {
    Metrics.addRegistry(meterRegistry);
  }
}
