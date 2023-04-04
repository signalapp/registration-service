/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;

/**
 * An "attempt pending analysis" listener listens for completed sessions and stores information about those attempts
 * for follow-up analysis (i.e. gathering pricing information).
 */
@Requires(bean = AttemptPendingAnalysisRepository.class)
@Singleton
public class AttemptPendingAnalysisEventListener implements ApplicationEventListener<SessionCompletedEvent> {

  private final AttemptPendingAnalysisRepository repository;

  public AttemptPendingAnalysisEventListener(final AttemptPendingAnalysisRepository repository) {
    this.repository = repository;
  }

  @Override
  public void onApplicationEvent(final SessionCompletedEvent event) {
    getAttemptsFromSession(event.session()).stream()
        .filter(attemptPendingAnalysis -> StringUtils.isNotBlank(attemptPendingAnalysis.getRemoteId()))
        .forEach(repository::store);
  }

  private static List<AttemptPendingAnalysis> getAttemptsFromSession(final RegistrationSession session) {
    final Phonenumber.PhoneNumber phoneNumber;

    try {
      phoneNumber = PhoneNumberUtil.getInstance().parse(session.getPhoneNumber(), null);
    } catch (final NumberParseException e) {
      // This should never happen; we've already parsed the number at least once if it's been stored in the session
      throw new AssertionError("Previously-parsed number could not be parsed", e);
    }

    final List<AttemptPendingAnalysis> attemptsPendingAnalysis = new ArrayList<>(session.getCheckCodeAttempts());

    for (int i = 0; i < session.getRegistrationAttemptsCount(); i++) {
      final RegistrationAttempt registrationAttempt = session.getRegistrationAttempts(i);

      final boolean attemptVerified =
          i == session.getRegistrationAttemptsCount() - 1 && StringUtils.isNotBlank(session.getVerifiedCode());

      attemptsPendingAnalysis.add(AttemptPendingAnalysis.newBuilder()
          .setSessionId(session.getId())
          .setAttemptId(i)
          .setSenderName(registrationAttempt.getSenderName())
          .setRemoteId(registrationAttempt.getRemoteId())
          .setMessageTransport(registrationAttempt.getMessageTransport())
          .setClientType(registrationAttempt.getClientType())
          .setRegion(StringUtils.defaultIfBlank(PhoneNumberUtil.getInstance().getRegionCodeForNumber(phoneNumber), "XX"))
          .setTimestampEpochMillis(registrationAttempt.getTimestampEpochMillis())
          .setAccountExistsWithE164(session.getSessionMetadata().getAccountExistsWithE164())
          .setVerified(attemptVerified)
          .build());
    }

    return attemptsPendingAnalysis;
  }
}
