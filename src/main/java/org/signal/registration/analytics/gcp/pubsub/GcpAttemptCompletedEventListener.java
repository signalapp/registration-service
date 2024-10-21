/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.gcp.pubsub;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.concurrent.Executor;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.session.FailedSendAttempt;
import org.signal.registration.session.FailedSendReason;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;
import org.signal.registration.util.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for completed sessions and stores information about those attempts for analysis (e.g. verification ratios)
 */
@Singleton
class GcpAttemptCompletedEventListener implements ApplicationEventListener<SessionCompletedEvent> {

  private static final Logger logger = LoggerFactory.getLogger(GcpAttemptCompletedEventListener.class);

  private final AttemptCompletedPubSubClient attemptCompletedPubSubClient;
  private final Executor executor;
  private final MeterRegistry meterRegistry;
  private final Timer startPublishTimer;
  private final Timer publishTimer;


  private static final String EVENT_PROCESSED_COUNTER_NAME =
      MetricsUtil.name(GcpAttemptCompletedEventListener.class, "eventProcessed");

  private static final String ATTEMPT_PUBLISHED_COUNTER_NAME =
      MetricsUtil.name(GcpAttemptCompletedEventListener.class, "attemptPublished");

  public GcpAttemptCompletedEventListener(
      final MeterRegistry meterRegistry,
      final AttemptCompletedPubSubClient attemptCompletedPubSubClient,
      final @Named(TaskExecutors.BLOCKING) Executor executor) {

    this.meterRegistry = meterRegistry;
    this.attemptCompletedPubSubClient = attemptCompletedPubSubClient;
    this.executor = executor;
    this.startPublishTimer = meterRegistry.timer(MetricsUtil.name(getClass(), "startPublish"));
    this.publishTimer = meterRegistry.timer(MetricsUtil.name(getClass(), "publish"));
  }

  @Override
  public void onApplicationEvent(final SessionCompletedEvent event) {
    final RegistrationSession session = event.session();
    final Phonenumber.PhoneNumber phoneNumber = getPhoneNumber(session);
    final String region = StringUtils.defaultIfBlank(PhoneNumberUtil.getInstance().getRegionCodeForNumber(phoneNumber),
        "XX");

    for (int i = 0; i < session.getFailedAttemptsCount(); i++) {
      final FailedSendAttempt failedAttempt = session.getFailedAttempts(i);
      if (failedAttempt.getFailedSendReason() != FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE) {
        // Only ding a sender's verification ratio if it was unavailable. If the provider indicated that the message
        // was undeliverable (either due to fraud, bad number, etc) assume that's accurate and don't penalize for it
        continue;
      }

      final CompletedAttemptPubSubMessage completedAttempt = CompletedAttemptPubSubMessage.newBuilder()
          .setSessionId(UUIDUtil.uuidFromByteString(session.getId()).toString())
          .setAttemptId(i)
          .setSenderName(failedAttempt.getSenderName())
          .setMessageTransport(MetricsUtil.getMessageTransportTagValue(failedAttempt.getMessageTransport()))
          .setClientType(MetricsUtil.getClientTypeTagValue(failedAttempt.getClientType()))
          .setRegion(region)
          .setTimestamp(Instant.ofEpochMilli(failedAttempt.getTimestampEpochMillis()).toString())
          .setAccountExistsWithE164(session.getSessionMetadata().getAccountExistsWithE164())
          .setVerified(false)
          .setSelectionReason(failedAttempt.getSelectionReason())
          .build();

      publish(completedAttempt);
    }

    for (int i = 0; i < session.getRegistrationAttemptsCount(); i++) {
      final RegistrationAttempt registrationAttempt = session.getRegistrationAttempts(i);
      if (StringUtils.isBlank(registrationAttempt.getRemoteId())) {
        continue;
      }

      final boolean attemptVerified =
          i == session.getRegistrationAttemptsCount() - 1 && StringUtils.isNotBlank(session.getVerifiedCode());

      meterRegistry.counter(EVENT_PROCESSED_COUNTER_NAME).increment();

      final CompletedAttemptPubSubMessage completedAttempt = CompletedAttemptPubSubMessage.newBuilder()
          .setSessionId(UUIDUtil.uuidFromByteString(session.getId()).toString())
          .setAttemptId(i)
          .setSenderName(registrationAttempt.getSenderName())
          .setMessageTransport(MetricsUtil.getMessageTransportTagValue(registrationAttempt.getMessageTransport()))
          .setClientType(MetricsUtil.getClientTypeTagValue(registrationAttempt.getClientType()))
          .setRegion(region)
          .setTimestamp(Instant.ofEpochMilli(registrationAttempt.getTimestampEpochMillis()).toString())
          .setAccountExistsWithE164(session.getSessionMetadata().getAccountExistsWithE164())
          .setVerified(attemptVerified)
          .setSelectionReason(registrationAttempt.getSelectionReason())
          .build();

      publish(completedAttempt);
    }
  }

  private void publish(final CompletedAttemptPubSubMessage completedAttempt) {
    final Timer.Sample startPublishSample = Timer.start();
    final Timer.Sample totalPublishSample = Timer.start();

    // immediately add to table of finished attempts (sans analysis)
    executor.execute(() -> {
      boolean success = false;

      try {
        attemptCompletedPubSubClient.send(completedAttempt.toByteArray());
        success = true;
      } catch (final Exception e) {
        logger.warn("Error processing session completion event", e);
      } finally {
        meterRegistry.counter(ATTEMPT_PUBLISHED_COUNTER_NAME,
                MetricsUtil.SUCCESS_TAG_NAME, String.valueOf(success))
            .increment();

        totalPublishSample.stop(publishTimer);
      }
    });

    startPublishSample.stop(startPublishTimer);
  }

  private static Phonenumber.PhoneNumber getPhoneNumber(final RegistrationSession session) {
    try {
      return PhoneNumberUtil.getInstance().parse(session.getPhoneNumber(), null);
    } catch (final NumberParseException e) {
      // This should never happen; we've already parsed the number at least once if it's been stored in the session
      throw new AssertionError("Previously-parsed number could not be parsed", e);
    }
  }
}
