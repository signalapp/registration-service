/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit.firestore;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.bind.annotation.Bindable;
import java.time.Duration;

@Context
@EachProperty("rate-limits.firestore")
public record FirestoreLeakyBucketRateLimiterConfiguration(@Parameter String name,
                                                           String collectionName,
                                                           @Bindable(defaultValue = "expiration") String expirationFieldName,
                                                           int maxCapacity,
                                                           Duration permitRegenerationPeriod,
                                                           Duration minDelay) {
}
