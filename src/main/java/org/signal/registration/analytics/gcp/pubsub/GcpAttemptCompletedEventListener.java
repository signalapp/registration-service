/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.gcp.pubsub;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.scheduling.annotation.Async;
import jakarta.inject.Singleton;
import java.time.Instant;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.metrics.MetricsUtil;
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
public class GcpAttemptCompletedEventListener implements ApplicationEventListener<SessionCompletedEvent> {
  private static final Logger logger = LoggerFactory.getLogger(GcpAttemptCompletedEventListener.class);

  private final CompletedAttemptPubSubMessageClient pubSubMessageClient;
  private final MeterRegistry meterRegistry;

  private static final String EVENT_PROCESSED_COUNTER_NAME =
      MetricsUtil.name(GcpAttemptCompletedEventListener.class, "eventProcessed");

  private static final String ATTEMPT_PUBLISHED_COUNTER_NAME =
      MetricsUtil.name(GcpAttemptCompletedEventListener.class, "attemptPublished");

  public GcpAttemptCompletedEventListener(
      final MeterRegistry meterRegistry,
      final CompletedAttemptPubSubMessageClient pubSubMessageClient) {
    this.meterRegistry = meterRegistry;
    this.pubSubMessageClient = pubSubMessageClient;
  }

  @Override
  @Async
  public void onApplicationEvent(final SessionCompletedEvent event) {
    final RegistrationSession session = event.session();
    final Phonenumber.PhoneNumber phoneNumber = getPhoneNumber(session);
    final String region = StringUtils.defaultIfBlank(PhoneNumberUtil.getInstance().getRegionCodeForNumber(phoneNumber), "XX");

    for (int i = 0; i < event.session().getRegistrationAttemptsCount(); i++) {
      final RegistrationAttempt registrationAttempt = event.session().getRegistrationAttempts(i);
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

      // immediately added table of finished attempts (sans analysis)
      boolean success = false;
      try {
        pubSubMessageClient.send(completedAttempt.toByteArray());
        success = true;
      } catch (Exception e) {
        logger.warn("Error processing session completion event", e);
      } finally {
        meterRegistry.counter(ATTEMPT_PUBLISHED_COUNTER_NAME, MetricsUtil.SUCCESS_TAG_NAME, String.valueOf(success))
            .increment();
      }
    }
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
