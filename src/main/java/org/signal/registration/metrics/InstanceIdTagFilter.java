/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.metrics;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.signal.registration.util.InstanceIdSupplier;

@Factory
public class InstanceIdTagFilter {

  @Bean
  @Singleton
  MeterFilter instanceIdTagFilter() {
    return MeterFilter.commonTags(Tags.of("instance", InstanceIdSupplier.getInstanceId()));
  }
}
