/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.infobip;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.validation.constraints.NotBlank;

@Context
@ConfigurationProperties("infobip")
public record InfobipClientConfiguration(@NotBlank String apiKey, @NotBlank String baseUrl) {}
