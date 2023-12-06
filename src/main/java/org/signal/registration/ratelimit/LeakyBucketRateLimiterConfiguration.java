/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.bind.annotation.Bindable;
import java.time.Duration;

@EachProperty("rate-limits.leaky-bucket")
public record LeakyBucketRateLimiterConfiguration(@Parameter String name,
                                                  @Bindable(defaultValue = "5") int maxCapacity,
                                                  @Bindable(defaultValue = "288m") Duration permitRegenerationPeriod,
                                                  @Bindable(defaultValue = "1m") Duration minDelay) {
}
