/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.ratelimit.firestore;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.bind.annotation.Bindable;
import java.time.Duration;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;

@Context
@ConfigurationProperties("rate-limits.firestore.session-creation")
@Requires(property = "rate-limits.firestore.session-creation.collection-name")
@Requires(property = "rate-limits.firestore.session-creation.max-capacity")
@Requires(property = "rate-limits.firestore.session-creation.permit-regeneration-period")
@Requires(property = "rate-limits.firestore.session-creation.min-delay")
public record FirestoreSessionCreationRateLimiterConfiguration(@NotBlank String collectionName,
                                                               @Bindable(defaultValue = "expiration") String expirationFieldName,
                                                               @Positive int maxCapacity,
                                                               Duration permitRegenerationPeriod,
                                                               Duration minDelay) {
}
