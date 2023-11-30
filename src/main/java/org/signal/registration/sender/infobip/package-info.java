/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

@Configuration
@Requires(property = "infobip.api-key")
@Requires(property = "infobip.base-url")
package org.signal.registration.sender.infobip;

import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;
