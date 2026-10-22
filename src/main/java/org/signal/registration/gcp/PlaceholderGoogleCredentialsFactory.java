/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.gcp;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.gcp.credentials.GoogleCredentialsFactory;
import jakarta.inject.Singleton;
import org.signal.registration.Environments;

/// Provides placeholder Google credentials in the development environment. Some GCP modules (pub/sub in particular)
/// unconditionally create beans that depend on Google credentials in the default configuration.
@Factory
@Requires(env = Environments.DEVELOPMENT)
@Requires(notEnv = Environment.GOOGLE_COMPUTE)
class PlaceholderGoogleCredentialsFactory {

  @Singleton
  @Replaces(factory = GoogleCredentialsFactory.class, value = GoogleCredentials.class)
  GoogleCredentials googleCredentials() {
    return GoogleCredentials.create(new AccessToken("development", null));
  }
}
