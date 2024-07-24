/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.messagebird;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.signal.registration.sender.SenderIdSelectorConfiguration;

@Context
@ConfigurationProperties("messagebird")
public record MessageBirdSenderConfiguration(
    @NotBlank String defaultSenderId,
    Map<@NotBlank String, @NotBlank String> senderIdsByRegion) implements SenderIdSelectorConfiguration {}
