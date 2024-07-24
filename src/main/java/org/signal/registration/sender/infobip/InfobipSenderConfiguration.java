/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.infobip;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.signal.registration.sender.SenderIdSelectorConfiguration;

@Context
@ConfigurationProperties("infobip")
public record InfobipSenderConfiguration (
    @NotBlank String defaultSenderId,
    Map<@NotBlank String, @NotBlank String> senderIdsByRegion) implements SenderIdSelectorConfiguration
{}
