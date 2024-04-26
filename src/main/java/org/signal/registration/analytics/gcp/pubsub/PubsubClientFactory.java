/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.gcp.pubsub;

import com.google.cloud.pubsub.v1.Publisher;
import com.google.pubsub.v1.TopicName;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.io.IOException;

@Factory
@Requires(env = Environment.GOOGLE_COMPUTE)
public class PubsubClientFactory {
  public static final String COMPLETED_ATTEMPTS = "completed-attempts";

  @Singleton
  @Named(COMPLETED_ATTEMPTS)
  @Requires(property = "gcp.pubsub.project-id")
  @Requires(property = "analytics.pubsub.completed-attempts.topic")
  Publisher publisher(
      @Value("${gcp.pubsub.project-id}") final String projectId,
      @Value("${analytics.pubsub.completed-attempts.topic}") final String topic) throws IOException {
    return Publisher.newBuilder(TopicName.newBuilder()
            .setProject(projectId)
            .setTopic(topic)
            .build())
        .build();
  }
}
