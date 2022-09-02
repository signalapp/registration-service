/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.cli;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import java.util.concurrent.Executor;

/**
 * API key call credentials present an API key to a gRPC server via the {@code x-signal-api-key} {@link Metadata} key.
 */
class ApiKeyCallCredentials extends CallCredentials {

  private final String apiKey;

  private static final Metadata.Key<String> API_KEY_METADATA_KEY =
      Metadata.Key.of("x-signal-api-key", Metadata.ASCII_STRING_MARSHALLER);

  ApiKeyCallCredentials(final String apiKey) {
    this.apiKey = apiKey;
  }

  @Override
  public void applyRequestMetadata(final RequestInfo requestInfo,
      final Executor appExecutor,
      final MetadataApplier applier) {

    final Metadata metadata = new Metadata();
    metadata.put(API_KEY_METADATA_KEY, apiKey);

    applier.apply(metadata);
  }

  @Override
  public void thisUsesUnstableApi() {
  }
}
