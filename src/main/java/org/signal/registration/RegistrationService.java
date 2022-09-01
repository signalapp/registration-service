/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import com.google.i18n.phonenumbers.Phonenumber;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The registration service is the core orchestrator of registration business logic and manages registration sessions
 * and verification code sender selection.
 */
@Singleton
public class RegistrationService {

  private final SenderSelectionStrategy senderSelectionStrategy;
  private final SessionRepository sessionRepository;

  private static final Logger logger = LoggerFactory.getLogger(RegistrationService.class);

  /**
   * Constructs a new registration service that chooses verification code senders with the given strategy and stores
   * session data with the given session repository.
   *
   * @param senderSelectionStrategy the strategy to use to choose verification code senders
   * @param sessionRepository the repository to use to store session data
   */
  public RegistrationService(final SenderSelectionStrategy senderSelectionStrategy,
      final SessionRepository sessionRepository) {

    this.senderSelectionStrategy = senderSelectionStrategy;
    this.sessionRepository = sessionRepository;
  }

  /**
   * Selects a verification code sender for the given destination and sends a verification code, creating a new
   * registration session in the process.
   *
   * @param messageTransport the transport via which to send a verification code to the destination phone number
   * @param phoneNumber the phone number to which to send a verification code
   * @param languageRanges a prioritized list of languages in which to send the verification code
   * @param clientType the type of client receiving the verification code
   *
   * @return a future that yields the identifier of the newly-created registration session when the verification code
   * has been sent and the session has been stored
   */
  public CompletableFuture<UUID> sendRegistrationCode(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    final VerificationCodeSender sender =
        senderSelectionStrategy.chooseVerificationCodeSender(messageTransport, phoneNumber, languageRanges, clientType);

    return sender.sendVerificationCode(phoneNumber, languageRanges, clientType)
        .thenCompose(sessionData -> sessionRepository.createSession(phoneNumber, sender, sender.getSessionTtl(), sessionData));
  }

  /**
   * Checks whether a client-provided verification code matches the expected verification code for a given registration
   * session. The code may be verified by communicating with an external service, by checking stored session data, or
   * by comparing against a previously-accepted verification code for the same session (i.e. in the case of retried
   * requests due to an interrupted connection).
   *
   * @param sessionId an identifier for a registration session against which to check a verification code
   * @param verificationCode a client-provided verification code
   *
   * @return a future that yields {@code true} if the client-provided code matches the code expected in the given
   * session or {@code false} otherwise
   */
  public CompletableFuture<Boolean> checkRegistrationCode(final UUID sessionId, final String verificationCode) {
    return sessionRepository.getSession(sessionId)
        .thenCompose(session -> {
          // If a connection was interrupted, a caller may repeat a verification request. Check to see if we already
          // have a known verification code for this session and, if so, check the provided code against that code
          // instead of making a call upstream.
          if (StringUtils.isNotBlank(session.verifiedCode())) {
            return CompletableFuture.completedFuture(session.verifiedCode().equals(verificationCode));
          } else {
            return session.sender().checkVerificationCode(verificationCode, session.sessionData())
                .thenCompose(verified -> {
                  if (verified) {
                    // Store the known-verified code for future potentially-repeated calls
                    return sessionRepository.setSessionVerified(sessionId, verificationCode)
                        .thenApply(ignored -> true);
                  } else {
                    return CompletableFuture.completedFuture(false);
                  }
                });
          }
        })
        .exceptionally(throwable -> {
          if (!(throwable.getCause() instanceof SessionNotFoundException)) {
            logger.warn("Unexpected exception when retrieving session {}", sessionId, throwable);
          }

          return false;
        });
  }
}
