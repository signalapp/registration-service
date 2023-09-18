/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

@Configuration
@Requires(env = {Environment.GOOGLE_COMPUTE})
package org.signal.registration.analytics.gcp.pubsub;

import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
