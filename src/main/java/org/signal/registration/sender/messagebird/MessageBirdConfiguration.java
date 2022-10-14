/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.messagebird;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import java.util.Map;
import javax.validation.constraints.NotBlank;

@Context
@ConfigurationProperties("messagebird")
public record MessageBirdConfiguration(@NotBlank String accessKey) {}
