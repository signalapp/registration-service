/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.metrics.gcp;

import com.google.cloud.MetadataConfig;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micronaut.configuration.metrics.annotation.RequiresMetrics;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * An instance ID tag filter inserts the GCP instance ID in lieu of a hostname when running in GCP, where hostnames
 * aren't available.
 */
@RequiresMetrics
@Requires(env = "gcp")
@Factory
class InstanceIdTagFilter implements MeterFilter {

  private final String instanceId;

  InstanceIdTagFilter() {
    this.instanceId = MetadataConfig.getInstanceId();
  }

  @Bean
  @Singleton
  MeterFilter instanceIdTagFilter() {
    return MeterFilter.commonTags(Tags.of("host", instanceId));
  }
}
