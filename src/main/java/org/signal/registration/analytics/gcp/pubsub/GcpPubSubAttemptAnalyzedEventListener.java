/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.gcp.pubsub;

import com.google.common.annotations.VisibleForTesting;
import io.micronaut.context.event.ApplicationEventListener;
import jakarta.inject.Singleton;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.util.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;

/**
 * A GCP pub/sub "analyzed event" listener dispatches messages to a GCP pub/sub topic. Pub/sub messages emitted by this
 * listener use a schema that is compatible with a BigQuery subscriber.
 */
@Singleton
public class GcpPubSubAttemptAnalyzedEventListener implements ApplicationEventListener<AttemptAnalyzedEvent> {

  private final AttemptAnalyzedPubSubMessageClient pubSubClient;

  private static final Logger logger = LoggerFactory.getLogger(GcpPubSubAttemptAnalyzedEventListener.class);

  public GcpPubSubAttemptAnalyzedEventListener(final AttemptAnalyzedPubSubMessageClient pubSubClient) {
    this.pubSubClient = pubSubClient;
  }

  @Override
  public void onApplicationEvent(final AttemptAnalyzedEvent event) {
    try {
      pubSubClient.send(buildPubSubMessage(event).toByteArray());
    } catch (final Exception e) {
      logger.warn("Failed to send pub/sub message", e);
    }
  }

  @VisibleForTesting
  static AttemptAnalyzedPubSubMessage buildPubSubMessage(final AttemptAnalyzedEvent event) {
    final String messageTransport = switch (event.attemptPendingAnalysis().getMessageTransport()) {
      case MESSAGE_TRANSPORT_SMS -> "sms";
      case MESSAGE_TRANSPORT_VOICE -> "voice";
      case MESSAGE_TRANSPORT_UNSPECIFIED, UNRECOGNIZED -> "unrecognized";
    };

    final String clientType = switch (event.attemptPendingAnalysis().getClientType()) {
      case CLIENT_TYPE_IOS -> "ios";
      case CLIENT_TYPE_ANDROID_WITH_FCM -> "android-with-fcm";
      case CLIENT_TYPE_ANDROID_WITHOUT_FCM -> "android-without-fcm";
      case CLIENT_TYPE_UNSPECIFIED, UNRECOGNIZED -> "unrecognized";
    };

    final AttemptAnalyzedPubSubMessage.Builder pubSubMessageBuilder = AttemptAnalyzedPubSubMessage.newBuilder()
        .setSessionId(UUIDUtil.uuidFromByteString(event.attemptPendingAnalysis().getSessionId()).toString())
        .setAttemptId(event.attemptPendingAnalysis().getAttemptId())
        .setSenderName(event.attemptPendingAnalysis().getSenderName())
        .setMessageTransport(messageTransport)
        .setClientType(clientType)
        .setRegion(event.attemptPendingAnalysis().getRegion())
        .setTimestamp(Instant.ofEpochMilli(event.attemptPendingAnalysis().getTimestampEpochMillis()).toString())
        .setAccountExistsWithE164(event.attemptPendingAnalysis().getAccountExistsWithE164())
        .setVerified(event.attemptPendingAnalysis().getVerified());

    event.attemptAnalysis().price().ifPresent(price -> {
      pubSubMessageBuilder.setPrice(price.amount().toString());
      pubSubMessageBuilder.setCurrency(price.currency().getCurrencyCode());
    });

    event.attemptAnalysis().mcc().ifPresent(pubSubMessageBuilder::setSenderMcc);
    event.attemptAnalysis().mnc().ifPresent(pubSubMessageBuilder::setSenderMnc);

    return pubSubMessageBuilder.build();
  }
}
