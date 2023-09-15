/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.scheduling.annotation.Async;
import jakarta.inject.Singleton;
import java.time.Instant;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.analytics.gcp.pubsub.CompletedAttemptPubSubMessage;
import org.signal.registration.analytics.gcp.pubsub.CompletedAttemptPubSubMessageClient;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;
import org.signal.registration.util.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An "attempt pending analysis" listener listens for completed sessions and stores information about those attempts
 * for follow-up analysis (i.e. gathering pricing information).
 */
@Requires(bean = AttemptPendingAnalysisRepository.class)
@Singleton
public class AttemptPendingAnalysisEventListener implements ApplicationEventListener<SessionCompletedEvent> {
  private static final Logger logger = LoggerFactory.getLogger(AttemptPendingAnalysisEventListener.class);

  private final CompletedAttemptPubSubMessageClient pubSubMessageClient;
  private final AttemptPendingAnalysisRepository repository;
  private final MeterRegistry meterRegistry;

  private static final String EVENT_PROCESSED_COUNTER_NAME =
      MetricsUtil.name(AttemptPendingAnalysisEventListener.class, "eventProcessed");

  private static final String REPOSITORY_UPDATED_COUNTER_NAME =
      MetricsUtil.name(AttemptPendingAnalysisEventListener.class, "pendingRepositoryUpdated");

  private static final String ATTEMPT_PUBLISHED_COUNTER_NAME =
      MetricsUtil.name(AttemptPendingAnalysisEventListener.class, "attemptPublished");

  public AttemptPendingAnalysisEventListener(
      final AttemptPendingAnalysisRepository repository,
      final MeterRegistry meterRegistry,
      final CompletedAttemptPubSubMessageClient pubSubMessageClient) {

    this.repository = repository;
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

      final AttemptPendingAnalysis attemptPendingAnalysis = AttemptPendingAnalysis.newBuilder()
          .setSessionId(session.getId())
          .setAttemptId(i)
          .setSenderName(registrationAttempt.getSenderName())
          .setRemoteId(registrationAttempt.getRemoteId())
          .setMessageTransport(registrationAttempt.getMessageTransport())
          .setClientType(registrationAttempt.getClientType())
          .setRegion(region)
          .setTimestampEpochMillis(registrationAttempt.getTimestampEpochMillis())
          .setAccountExistsWithE164(session.getSessionMetadata().getAccountExistsWithE164())
          .setVerified(attemptVerified)
          .build();


      meterRegistry.counter(EVENT_PROCESSED_COUNTER_NAME).increment();

      // stored temporarily while waiting for additional analysis information from providers
      boolean repositoryUpdated = tryOperation(() -> repository.store(attemptPendingAnalysis).join());
      meterRegistry.counter(REPOSITORY_UPDATED_COUNTER_NAME, MetricsUtil.SUCCESS_TAG_NAME, String.valueOf(repositoryUpdated))
          .increment();

      // immediately added table of finished attempts (sans analysis)
      boolean published = tryOperation(() -> pubSubMessageClient.send(pubSubMessage(attemptPendingAnalysis, registrationAttempt).toByteArray()));
      meterRegistry.counter(ATTEMPT_PUBLISHED_COUNTER_NAME, MetricsUtil.SUCCESS_TAG_NAME, String.valueOf(published))
          .increment();
    }
  }

  private static boolean tryOperation(final Runnable runnable) {
    try {
      runnable.run();
      return true;
    } catch (final Exception e) {
      logger.warn("Error processing session completion event", e);
      return false;
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

  private static CompletedAttemptPubSubMessage pubSubMessage(
      final AttemptPendingAnalysis attemptPendingAnalysis,
      final RegistrationAttempt registrationAttempt) {
    return CompletedAttemptPubSubMessage.newBuilder()
        .setSessionId(UUIDUtil.uuidFromByteString(attemptPendingAnalysis.getSessionId()).toString())
        .setAttemptId(attemptPendingAnalysis.getAttemptId())
        .setSenderName(attemptPendingAnalysis.getSenderName())
        .setMessageTransport(
            MetricsUtil.getMessageTransportTagValue(attemptPendingAnalysis.getMessageTransport()))
        .setClientType(MetricsUtil.getClientTypeTagValue(attemptPendingAnalysis.getClientType()))
        .setRegion(attemptPendingAnalysis.getRegion())
        .setTimestamp(Instant.ofEpochMilli(attemptPendingAnalysis.getTimestampEpochMillis()).toString())
        .setAccountExistsWithE164(attemptPendingAnalysis.getAccountExistsWithE164())
        .setVerified(attemptPendingAnalysis.getVerified())
        .setSelectionReason(registrationAttempt.getSelectionReason())
        .build();
  }
}
