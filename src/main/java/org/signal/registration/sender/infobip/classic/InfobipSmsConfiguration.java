/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.infobip.classic;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/**
 * @param sessionTtl How long verification sessions are valid for
 */
@Context
@ConfigurationProperties("infobip.sms")
public record InfobipSmsConfiguration (
    @Bindable(defaultValue = "PT10M") @NotNull Duration sessionTtl
) {}
