/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit.firestore;

import io.micronaut.core.bind.annotation.Bindable;
import java.time.Duration;

public record FirestoreLeakyBucketRateLimiterConfiguration(String collectionName,
                                                           @Bindable(defaultValue = "expiration") String expirationFieldName,
                                                           int maxCapacity,
                                                           Duration permitRegenerationPeriod,
                                                           Duration minDelay) {
}
