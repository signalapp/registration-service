/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.firestore;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import javax.validation.constraints.NotBlank;

/**
 * Defines configuration properties for a {@link FirestoreSessionRepository}.
 *
 * @param collectionName the name of the Firestore collection in which session data will be stored
 * @param expirationFieldName the name of the document field that identifies when registration sessions have expired and
 *                            should no longer be returned by session retrieval/modification operations
 * @param removalFieldName the name of the document field that identifies when registration sessions should be removed
 *                         by Firestore's garbage collection system if they have not already been deleted by other means
 */
@ConfigurationProperties("session-repository.firestore")
record FirestoreSessionRepositoryConfiguration(@Bindable(defaultValue = "registration-sessions") @NotBlank String collectionName,
                                               @Bindable(defaultValue = "expiration") @NotBlank String expirationFieldName,
                                               @Bindable(defaultValue = "removal") @NotBlank String removalFieldName) {
}
