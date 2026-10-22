/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.bigtable;

import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties("session-repository.bigtable")
@Requires(bean = BigtableDataClient.class)
record BigtableSessionRepositoryConfiguration(@NotBlank String tableName,
                                              @NotBlank String columnFamilyName) {

}
