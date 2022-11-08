/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.session.RegistrationAttempt;
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
  private final Clock clock;

  private final Map<String, VerificationCodeSender> sendersByName;

  @VisibleForTesting
  static final Duration NEW_SESSION_TTL = Duration.ofMinutes(10);

  private static final Logger logger = LoggerFactory.getLogger(RegistrationService.class);

  private record SenderAndSessionData(VerificationCodeSender sender, byte[] sessionData) {};

  /**
   * Constructs a new registration service that chooses verification code senders with the given strategy and stores
   * session data with the given session repository.
   *
   * @param senderSelectionStrategy the strategy to use to choose verification code senders
   * @param sessionRepository       the repository to use to store session data
   * @param verificationCodeSenders a list of verification code senders that may be used by this service
   * @param clock                   the time source for this registration service
   */
  public RegistrationService(final SenderSelectionStrategy senderSelectionStrategy,
      final SessionRepository sessionRepository,
      final List<VerificationCodeSender> verificationCodeSenders,
      final Clock clock) {

    this.senderSelectionStrategy = senderSelectionStrategy;
    this.sessionRepository = sessionRepository;
    this.clock = clock;

    this.sendersByName = verificationCodeSenders.stream()
        .collect(Collectors.toMap(VerificationCodeSender::getName, sender -> sender));
  }

  /**
   * Selects a verification code sender for the given destination and sends a verification code, creating a new
   * registration session in the process if a phone number is provided or updating an existing session if a session ID
   * is provided.
   *
   * @param messageTransport the transport via which to send a verification code to the destination phone number
   * @param phoneNumber the phone number to which to send a verification code; may be {@code null} if a session ID is
   *                    provided instead
   * @param sessionId if specified, resends a verification code in the context of an existing session; may be
   *                  {@code null} if a phone number is provided instead
   * @param languageRanges a prioritized list of languages in which to send the verification code
   * @param clientType the type of client receiving the verification code
   *
   * @return a future that yields the identifier of the newly-created registration session when the verification code
   * has been sent and the session has been stored
   */
  public CompletableFuture<UUID> sendRegistrationCode(final MessageTransport messageTransport,
      @Nullable final Phonenumber.PhoneNumber phoneNumber,
      @Nullable final UUID sessionId,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    if (phoneNumber == null && sessionId == null) {
      throw new IllegalArgumentException("Must specify a phone number or session ID");
    }

    return sessionId != null ?
        sendRegistrationCode(messageTransport, sessionId, languageRanges, clientType) :
        sessionRepository.createSession(phoneNumber, NEW_SESSION_TTL)
            .thenCompose(createdSessionId -> sendRegistrationCode(messageTransport, createdSessionId, languageRanges, clientType));
  }

  private CompletableFuture<UUID> sendRegistrationCode(final MessageTransport messageTransport,
      final UUID sessionId,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    return sessionRepository.getSession(sessionId)
        .thenCompose(session -> {
          if (StringUtils.isBlank(session.getVerifiedCode())) {
            try {
              final Phonenumber.PhoneNumber phoneNumberFromSession =
                  PhoneNumberUtil.getInstance().parse(session.getPhoneNumber(), null);

              final VerificationCodeSender sender =
                  senderSelectionStrategy.chooseVerificationCodeSender(messageTransport, phoneNumberFromSession, languageRanges, clientType);

              return sender.sendVerificationCode(messageTransport,
                      phoneNumberFromSession,
                      languageRanges,
                      clientType)
                  .thenApply(sessionData -> new SenderAndSessionData(sender, sessionData));
            } catch (final NumberParseException e) {
              throw new CompletionException(e);
            }
          } else {
            throw new IllegalStateException("Session already has a verified code");
          }
        })
        .thenCompose(senderAndSessionData -> sessionRepository.updateSession(sessionId, session -> session.toBuilder()
            .addRegistrationAttempts(buildRegistrationAttempt(senderAndSessionData.sender(), messageTransport, senderAndSessionData.sessionData()))
            .build(), senderAndSessionData.sender().getSessionTtl()))
        .thenApply(ignored -> sessionId);
  }

  private RegistrationAttempt buildRegistrationAttempt(final VerificationCodeSender sender,
      final MessageTransport messageTransport,
      final byte[] sessionData) {

    final org.signal.registration.session.MessageTransport sessionMessageTransport = switch (messageTransport) {
      case SMS -> org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS;
      case VOICE -> org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_VOICE;
    };

    return RegistrationAttempt.newBuilder()
        .setTimestamp(clock.millis())
        .setSenderName(sender.getName())
        .setMessageTransport(sessionMessageTransport)
        .setSessionData(ByteString.copyFrom(sessionData))
        .build();
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
          if (StringUtils.isNotBlank(session.getVerifiedCode())) {
            return CompletableFuture.completedFuture(session.getVerifiedCode().equals(verificationCode));
          } else {
            if (session.getRegistrationAttemptsCount() == 0) {
              throw new IllegalArgumentException("Session does not have any associated verification attempts");
            }

            final RegistrationAttempt currentRegistrationAttempt =
                session.getRegistrationAttempts(session.getRegistrationAttemptsCount() - 1);

            final VerificationCodeSender sender = sendersByName.get(currentRegistrationAttempt.getSenderName());

            if (sender == null) {
              throw new IllegalArgumentException("Unrecognized sender: " + currentRegistrationAttempt.getSenderName());
            }

            return sender.checkVerificationCode(verificationCode, currentRegistrationAttempt.getSessionData().toByteArray())
                .thenCompose(verified -> {
                  if (verified) {
                    // Store the known-verified code for future potentially-repeated calls
                    return sessionRepository.updateSession(sessionId,
                            s -> s.toBuilder().setVerifiedCode(verificationCode).build(), null)
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
