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
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionRepository;

/**
 * The registration service is the core orchestrator of registration business logic and manages registration sessions
 * and verification code sender selection.
 */
@Singleton
public class RegistrationService {

  private final SenderSelectionStrategy senderSelectionStrategy;
  private final SessionRepository sessionRepository;
  private final RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter;
  private final Clock clock;

  private final Map<String, VerificationCodeSender> sendersByName;

  @VisibleForTesting
  static final Duration NEW_SESSION_TTL = Duration.ofMinutes(10);

  private record SenderAndSessionData(VerificationCodeSender sender, byte[] sessionData) {}

  /**
   * Constructs a new registration service that chooses verification code senders with the given strategy and stores
   * session data with the given session repository.
   *
   * @param senderSelectionStrategy    the strategy to use to choose verification code senders
   * @param sessionRepository          the repository to use to store session data
   * @param sessionCreationRateLimiter a rate limiter that controls the rate at which sessions may be created for
   *                                   individual phone numbers
   * @param verificationCodeSenders    a list of verification code senders that may be used by this service
   * @param clock                      the time source for this registration service
   */
  public RegistrationService(final SenderSelectionStrategy senderSelectionStrategy,
      final SessionRepository sessionRepository,
      @Named("session-creation") final RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter,
      final List<VerificationCodeSender> verificationCodeSenders,
      final Clock clock) {

    this.senderSelectionStrategy = senderSelectionStrategy;
    this.sessionRepository = sessionRepository;
    this.sessionCreationRateLimiter = sessionCreationRateLimiter;
    this.clock = clock;

    this.sendersByName = verificationCodeSenders.stream()
        .collect(Collectors.toMap(VerificationCodeSender::getName, Function.identity()));
  }

  /**
   * Creates a new registration session for the given phone number.
   *
   * @param phoneNumber the phone number for which to create a new registration session
   *
   * @return a future that yields the newly-created registration session once the session has been created and stored in
   * this service's session repository; the returned future may fail with a
   * {@link org.signal.registration.ratelimit.RateLimitExceededException}
   */
  public CompletableFuture<RegistrationSession> createRegistrationSession(final Phonenumber.PhoneNumber phoneNumber) {
    return sessionCreationRateLimiter.checkRateLimit(phoneNumber)
        .thenCompose(ignored -> sessionRepository.createSession(phoneNumber, NEW_SESSION_TTL));
  }

  /**
   * Selects a verification code sender for the destination phone number associated with the given session and sends a
   * verification code.
   *
   * @param messageTransport the transport via which to send a verification code to the destination phone number
   * @param sessionId the session within which to send (or re-send) a verification code
   * @param senderName if specified, a preferred sender to use
   * @param languageRanges a prioritized list of languages in which to send the verification code
   * @param clientType the type of client receiving the verification code
   *
   * @return a future that yields the updated registration session when the verification code has been sent and updates
   * to the session have been stored
   */
  public CompletableFuture<RegistrationSession> sendRegistrationCode(final MessageTransport messageTransport,
      final UUID sessionId,
      @Nullable final String senderName,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType) {

    return sessionRepository.getSession(sessionId)
        .thenCompose(session -> {
          if (StringUtils.isBlank(session.getVerifiedCode())) {
            try {
              final Phonenumber.PhoneNumber phoneNumberFromSession =
                  PhoneNumberUtil.getInstance().parse(session.getPhoneNumber(), null);

              final VerificationCodeSender sender = senderSelectionStrategy.chooseVerificationCodeSender(
                  messageTransport, phoneNumberFromSession, languageRanges, clientType, senderName);

              return sender.sendVerificationCode(messageTransport,
                      phoneNumberFromSession,
                      languageRanges,
                      clientType)
                  .thenApply(sessionData -> new SenderAndSessionData(sender, sessionData));
            } catch (final NumberParseException e) {
              // This should never happen because we're parsing a phone number from the session, which means we've
              // parsed it successfully in the past
              throw new CompletionException(e);
            }
          } else {
            return CompletableFuture.failedFuture(new SessionAlreadyVerifiedException(session));
          }
        })
        .thenCompose(senderAndSessionData -> sessionRepository.updateSession(sessionId, session -> session.toBuilder()
            .addRegistrationAttempts(buildRegistrationAttempt(senderAndSessionData.sender(), messageTransport, senderAndSessionData.sessionData()))
            .build(), senderAndSessionData.sender().getSessionTtl()));
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
   * @return a future that yields the updated registration; the session's {@code verifiedCode} field will be set if the
   * session has been successfully verified
   */
  public CompletableFuture<RegistrationSession> checkRegistrationCode(final UUID sessionId, final String verificationCode) {
    return sessionRepository.getSession(sessionId)
        .thenCompose(session -> {
          // If a connection was interrupted, a caller may repeat a verification request. Check to see if we already
          // have a known verification code for this session and, if so, check the provided code against that code
          // instead of making a call upstream.
          if (StringUtils.isNotBlank(session.getVerifiedCode())) {
            return CompletableFuture.completedFuture(session);
          } else {
            if (session.getRegistrationAttemptsCount() == 0) {
              return CompletableFuture.failedFuture(new NoVerificationCodeSentException(session));
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
                            s -> s.toBuilder().setVerifiedCode(verificationCode).build(), null);
                  } else {
                    return CompletableFuture.completedFuture(session);
                  }
                });
          }
        });
  }
}
