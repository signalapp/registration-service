/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.messagebird;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.validation.constraints.NotBlank;

@Context
@ConfigurationProperties("messagebird")
public record MessageBirdClientConfiguration(@NotBlank String accessKey) {
}
