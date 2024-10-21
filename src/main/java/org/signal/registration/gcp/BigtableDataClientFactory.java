/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.gcp;

import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.io.IOException;

@Factory
@Requires(env = {Environment.GOOGLE_COMPUTE, Environment.CLI})
@Requires(property = "gcp.project-id")
@Requires(property = "gcp.bigtable.instance-id")
public class BigtableDataClientFactory {

  @Singleton
  BigtableDataClient bigtableDataClient(@Value("${gcp.project-id}") final String projectId,
      @Value("${gcp.bigtable.instance-id}") final String instanceId) throws IOException {

    return BigtableDataClient.create(projectId, instanceId);
  }
}
