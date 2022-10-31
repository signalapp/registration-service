/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.gcp;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.io.IOException;

@Requires(env = "gcp")
@Factory
class FirestoreClientFactory {

  @Singleton
  Firestore firestore() {
    return FirestoreOptions.getDefaultInstance().toBuilder()
        .build()
        .getService();
  }
}
