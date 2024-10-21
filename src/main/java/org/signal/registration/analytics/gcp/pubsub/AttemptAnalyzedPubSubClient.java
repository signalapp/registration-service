/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.gcp.pubsub;

import io.micronaut.context.annotation.Requires;
import io.micronaut.gcp.pubsub.annotation.PubSubClient;
import io.micronaut.gcp.pubsub.annotation.Topic;

@PubSubClient
@Requires(property = "analytics.pubsub.analyzed-attempts.topic")
interface AttemptAnalyzedPubSubClient {

  @Topic(value = "${analytics.pubsub.analyzed-attempts.topic}", contentType = "application/protobuf", configuration = "analytics")
  void send(byte[] attemptAnalyzedPubSubMessage);
}
