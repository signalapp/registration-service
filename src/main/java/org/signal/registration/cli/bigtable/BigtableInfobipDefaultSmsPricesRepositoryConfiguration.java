/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.cli.bigtable;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.NotBlank;

@ConfigurationProperties("infobip.sms.prices.bigtable")
public record BigtableInfobipDefaultSmsPricesRepositoryConfiguration(@NotBlank String tableId,
                                                                     @NotBlank String columnFamilyName) {}
