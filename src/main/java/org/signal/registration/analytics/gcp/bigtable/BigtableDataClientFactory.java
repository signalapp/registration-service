/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.gcp.bigtable;

import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.io.IOException;

@Factory
public class BigtableDataClientFactory {

  @Singleton
  BigtableDataClient bigtableDataClient(final BigtableAttemptPendingAnalysisRepositoryConfiguration configuration) throws IOException {
    return BigtableDataClient.create(configuration.projectId(), configuration.instanceId());
  }
}
