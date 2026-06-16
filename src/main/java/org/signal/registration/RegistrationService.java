/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import static org.signal.registration.sender.SenderSelectionStrategy.SenderSelection;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.rpc.RegistrationSessionMetadata;
import org.signal.registration.sender.AttemptData;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.NoSenderAvailableException;
import org.signal.registration.sender.SenderFraudBlockException;
import org.signal.registration.sender.SenderRateLimitedRequestException;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.SenderRejectedTransportException;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.session.FailedSendAttempt;
import org.signal.registration.session.FailedSendReason;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionMetadata;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import org.signal.registration.util.ClientTypes;
import org.signal.registration.util.MessageTransports;
import org.signal.registration.util.UUIDUtil;

/**
 * The registration service is the core orchestrator of registration business logic and manages registration sessions
 * and verification code sender selection.
 */
@Singleton
@Requires(notEnv = Environments.ANALYTICS)
public class RegistrationService {

  private final SenderSelectionStrategy senderSelectionStrategy;
  private final SessionRepository sessionRepository;
  private final RateLimiter<Pair<Phonenumber.PhoneNumber, String>> sessionCreationRateLimiter;
  private final RateLimiter<RegistrationSession> sendSmsVerificationCodePerSessionRateLimiter;
  private final RateLimiter<RegistrationSession> sendVoiceVerificationCodePerSessionRateLimiter;
  private final RateLimiter<RegistrationSession> checkVerificationCodePerSessionRateLimiter;
  private final RateLimiter<Phonenumber.PhoneNumber> sendSmsVerificationCodePerNumberRateLimiter;
  private final RateLimiter<Phonenumber.PhoneNumber> sendVoiceVerificationCodePerNumberRateLimiter;
  private final RateLimiter<Phonenumber.PhoneNumber> checkVerificationCodePerNumberRateLimiter;
  private final Clock clock;

  private final Map<String, VerificationCodeSender> sendersByName;

  @VisibleForTesting
  static final Duration SESSION_TTL_AFTER_LAST_ACTION = Duration.ofMinutes(10);


  @VisibleForTesting
  record NextActionTimes(Optional<Instant> nextSms,
                         Optional<Instant> nextVoiceCall,
                         Optional<Instant> nextCodeCheck) {

    static NextActionTimes EMPTY = new NextActionTimes(Optional.empty(), Optional.empty(), Optional.empty());
  }

  /**
   * Constructs a new registration service that chooses verification code senders with the given strategy and stores
   * session data with the given session repository.
   *
   * @param senderSelectionStrategy                        the strategy to use to choose verification code senders
   * @param sessionRepository                              the repository to use to store session data
   * @param sessionCreationRateLimiter                     a rate limiter that controls the rate at which sessions may
   *                                                       be created for individual phone numbers
   * @param sendSmsVerificationCodePerSessionRateLimiter   a rate limiter that controls the rate at which callers may
   *                                                       request verification codes via SMS for a given session
   * @param sendVoiceVerificationCodePerSessionRateLimiter a rate limiter that controls the rate at which callers may
   *                                                       request verification codes via voice call for a given session
   * @param checkVerificationCodePerSessionRateLimiter     a rate limiter that controls the rate and number of times a
   *                                                       caller may check a verification code for a given session
   * @param sendSmsVerificationCodePerNumberRateLimiter    a rate limiter that controls the rate at which callers may
   *                                                       request verification codes via SMS for a given number
   * @param sendVoiceVerificationCodePerNumberRateLimiter  a rate limiter that controls the rate at which callers may
   *                                                       request verification codes via voice call for a given number
   * @param checkVerificationCodePerNumberRateLimiter      a rate limiter that controls the rate and number of times a
   *                                                       caller may check a verification code for a given number
   * @param verificationCodeSenders                        a list of verification code senders that may be used by this
   *                                                       service
   * @param clock                                          the time source for this registration service
   */
  public RegistrationService(final SenderSelectionStrategy senderSelectionStrategy,
      final SessionRepository sessionRepository,
      @Named("session-creation") final RateLimiter<Pair<Phonenumber.PhoneNumber, String>> sessionCreationRateLimiter,
      @Named("send-sms-verification-code-per-session") final RateLimiter<RegistrationSession> sendSmsVerificationCodePerSessionRateLimiter,
      @Named("send-voice-verification-code-per-session") final RateLimiter<RegistrationSession> sendVoiceVerificationCodePerSessionRateLimiter,
      @Named("check-verification-code-per-session") final RateLimiter<RegistrationSession> checkVerificationCodePerSessionRateLimiter,
      @Named("send-sms-verification-code-per-number") final RateLimiter<Phonenumber.PhoneNumber> sendSmsVerificationCodePerNumberRateLimiter,
      @Named("send-voice-verification-code-per-number") final RateLimiter<Phonenumber.PhoneNumber> sendVoiceVerificationCodePerNumberRateLimiter,
      @Named("check-verification-code-per-number") final RateLimiter<Phonenumber.PhoneNumber> checkVerificationCodePerNumberRateLimiter,
      final List<VerificationCodeSender> verificationCodeSenders,
      final Clock clock) {

    this.senderSelectionStrategy = senderSelectionStrategy;
    this.sessionRepository = sessionRepository;
    this.sessionCreationRateLimiter = sessionCreationRateLimiter;
    this.sendSmsVerificationCodePerSessionRateLimiter = sendSmsVerificationCodePerSessionRateLimiter;
    this.sendVoiceVerificationCodePerSessionRateLimiter = sendVoiceVerificationCodePerSessionRateLimiter;
    this.checkVerificationCodePerSessionRateLimiter = checkVerificationCodePerSessionRateLimiter;
    this.sendSmsVerificationCodePerNumberRateLimiter = sendSmsVerificationCodePerNumberRateLimiter;
    this.sendVoiceVerificationCodePerNumberRateLimiter = sendVoiceVerificationCodePerNumberRateLimiter;
    this.checkVerificationCodePerNumberRateLimiter = checkVerificationCodePerNumberRateLimiter;
    this.clock = clock;

    this.sendersByName = verificationCodeSenders.stream()
        .collect(Collectors.toMap(VerificationCodeSender::getName, Function.identity()));
  }

  /**
   * Creates a new registration session for the given phone number.
   *
   * @param phoneNumber the phone number for which to create a new registration session
   *
   * @return the newly-created registration session
   *
   * @throws RateLimitExceededException if the caller must wait before creating another session for the given phone
   * number
   */
  public RegistrationSession createRegistrationSession(final Phonenumber.PhoneNumber phoneNumber,
      final String rateLimitCollationKey,
      final SessionMetadata sessionMetadata) throws RateLimitExceededException {

    sessionCreationRateLimiter.checkRateLimit(Pair.of(phoneNumber, rateLimitCollationKey));
    return sessionRepository.createSession(phoneNumber, sessionMetadata, clock.instant().plus(SESSION_TTL_AFTER_LAST_ACTION));
  }

  /**
   * Retrieves a registration session by its unique identifier.
   *
   * @param sessionId the unique identifier for the session to retrieve
   *
   * @return the identified session when complete
   *
   * @throws SessionNotFoundException if no session was found for the given identifier
   */
  public RegistrationSession getRegistrationSession(final UUID sessionId) throws SessionNotFoundException {
    return sessionRepository.getSession(sessionId);
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
   * @return the updated registration session
   *
   * @throws SessionNotFoundException if no session was found for the given identifier
   * @throws SessionAlreadyVerifiedException if the session is already verified
   * @throws TransportNotAllowedException if the selected sender does not support the given `messageTransport`
   * @throws RateLimitExceededException if the caller must wait before requesting another verification code
   * @throws SenderRejectedRequestException if the sender received but rejected the request to send a verification code
   * for any reason
   * @throws NoSenderAvailableException if there are no available senders in the region for the specified transport
   */
  public RegistrationSession sendVerificationCode(final MessageTransport messageTransport,
      final UUID sessionId,
      @Nullable final String senderName,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType)
      throws TransportNotAllowedException, SessionAlreadyVerifiedException, SessionNotFoundException, RateLimitExceededException, SenderRejectedRequestException, NoSenderAvailableException {

    final RateLimiter<RegistrationSession> sessionRateLimiter = switch (messageTransport) {
      case SMS -> sendSmsVerificationCodePerSessionRateLimiter;
      case VOICE -> sendVoiceVerificationCodePerSessionRateLimiter;
    };

    final RateLimiter<Phonenumber.PhoneNumber> numberRateLimiter = switch (messageTransport) {
      case SMS -> sendSmsVerificationCodePerNumberRateLimiter;
      case VOICE -> sendVoiceVerificationCodePerNumberRateLimiter;
    };

    final RegistrationSession session = sessionRepository.getSession(sessionId);

    if (StringUtils.isNotBlank(session.getVerifiedCode())) {
      throw new SessionAlreadyVerifiedException(session);
    }

    sessionRateLimiter.checkRateLimit(session);

    try {
      numberRateLimiter.checkRateLimit(getPhoneNumberFromSession(session));
    } catch (final RateLimitExceededException e) {
      // In general, leaky-bucket rate limiters don't know about registration sessions, so we need to add the session
      // on our own
      e.setRegistrationSession(session);
      throw e;
    }

    final Set<String> previouslyFailedSenders = session.getRegistrationAttemptsList()
        .stream()
        .map(RegistrationAttempt::getSenderName)
        .collect(Collectors.toSet());

    // Add senders from failed attempts with a "provider unavailable" error
    session.getFailedAttemptsList()
        .stream()
        .filter(attempt -> attempt.getFailedSendReason() == FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE)
        .map(FailedSendAttempt::getSenderName)
        .forEach(previouslyFailedSenders::add);

    final Phonenumber.PhoneNumber phoneNumberFromSession = getPhoneNumberFromSession(session);
    final SenderSelection selection = senderSelectionStrategy.chooseVerificationCodeSender(
        messageTransport, phoneNumberFromSession, languageRanges, clientType, senderName,
        previouslyFailedSenders);

    try {
      final AttemptData attemptData = selection.sender()
          .sendVerificationCode(messageTransport, phoneNumberFromSession, languageRanges, clientType);

      return sessionRepository.updateSession(sessionId, sessionToUpdate -> {
        final RegistrationSession.Builder builder = sessionToUpdate.toBuilder()
            .setCheckCodeAttempts(0)
            .setLastCheckCodeAttemptEpochMillis(0)
            .addRegistrationAttempts(buildRegistrationAttempt(selection,
                messageTransport,
                clientType,
                attemptData,
                selection.sender().getAttemptTtl()));

        final Instant expiration = getSessionExpiration(builder.build());

        return builder
            .setExpirationEpochMillis(expiration.toEpochMilli())
            .build();
      });
    } catch (final SenderRateLimitedRequestException e) {
      throw new RateLimitExceededException(e.getRetryAfter(), session);
    } catch (final SenderRejectedTransportException e) {
      final RegistrationSession updatedSession = sessionRepository.updateSession(sessionId, sessionToUpdate -> {
        final RegistrationSession.Builder builder = sessionToUpdate.toBuilder()
            .setCheckCodeAttempts(0)
            .setLastCheckCodeAttemptEpochMillis(0)
            .addRejectedTransports(MessageTransports.getRpcMessageTransportFromSenderTransport(messageTransport));

        final Instant expiration = getSessionExpiration(builder.build());

        return builder
            .setExpirationEpochMillis(expiration.toEpochMilli())
            .build();
      });

      throw new TransportNotAllowedException(e, updatedSession);
    } catch (final Exception e) {
      final FailedSendReason failedSendReason = switch (e) {
        case SenderFraudBlockException ignored -> FailedSendReason.FAILED_SEND_REASON_SUSPECTED_FRAUD;
        case SenderRejectedRequestException ignored -> FailedSendReason.FAILED_SEND_REASON_REJECTED;
        default -> FailedSendReason.FAILED_SEND_REASON_UNAVAILABLE;
      };

      sessionRepository.updateSession(sessionId,
          sessionToUpdate ->
              sessionToUpdate.toBuilder()
                  .addFailedAttempts(FailedSendAttempt.newBuilder()
                      .setTimestampEpochMillis(clock.instant().toEpochMilli())
                      .setSenderName(selection.sender().getName())
                      .setSelectionReason(selection.reason().toString())
                      .setMessageTransport(
                          MessageTransports.getRpcMessageTransportFromSenderTransport(messageTransport))
                      .setClientType(ClientTypes.getRpcClientTypeFromSenderClientType(clientType))
                      .setFailedSendReason(failedSendReason))
                  .build());

      throw e;
    }
  }

  private RegistrationAttempt buildRegistrationAttempt(final SenderSelection selection,
      final MessageTransport messageTransport,
      final ClientType clientType,
      final AttemptData attemptData,
      final Duration ttl) {

    final Instant currentTime = clock.instant();

    final RegistrationAttempt.Builder registrationAttemptBuilder = RegistrationAttempt.newBuilder()
        .setTimestampEpochMillis(currentTime.toEpochMilli())
        .setExpirationEpochMillis(currentTime.plus(ttl).toEpochMilli())
        .setSenderName(selection.sender().getName())
        .setSelectionReason(selection.reason().toString())
        .setMessageTransport(MessageTransports.getRpcMessageTransportFromSenderTransport(messageTransport))
        .setClientType(ClientTypes.getRpcClientTypeFromSenderClientType(clientType))
        .setSenderData(ByteString.copyFrom(attemptData.senderData()));

    attemptData.remoteId().ifPresent(registrationAttemptBuilder::setRemoteId);

    return registrationAttemptBuilder.build();
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
   * @return the updated registration; the session's {@code verifiedCode} field will be set if the session has been
   * successfully verified
   */
  public RegistrationSession checkVerificationCode(final UUID sessionId, final String verificationCode)
      throws SessionNotFoundException, NoVerificationCodeSentException, RateLimitExceededException, AttemptExpiredException {

    final RegistrationSession session = sessionRepository.getSession(sessionId);

    // If a connection was interrupted, a caller may repeat a verification request. If a previous code was already
    // verified, we can return the existing verified session without making another call to actually check the code
    // (again).
    if (StringUtils.isNotBlank(session.getVerifiedCode())) {
      return session;
    }

    return checkVerificationCode(session, verificationCode);
  }

  private RegistrationSession checkVerificationCode(final RegistrationSession session, final String verificationCode)
      throws NoVerificationCodeSentException, RateLimitExceededException, SessionNotFoundException, AttemptExpiredException {

    if (session.getRegistrationAttemptsCount() == 0) {
      throw new NoVerificationCodeSentException(session);
    }

    checkVerificationCodePerSessionRateLimiter.checkRateLimit(session);

    try {
      checkVerificationCodePerNumberRateLimiter.checkRateLimit(getPhoneNumberFromSession(session));
    } catch (final RateLimitExceededException e) {
      // In general, leaky-bucket rate limiters don't know about registration sessions, so we need to add the session
      // on our own
      e.setRegistrationSession(session);
      throw e;
    }

    final RegistrationAttempt currentRegistrationAttempt =
        session.getRegistrationAttempts(session.getRegistrationAttemptsCount() - 1);

    if (Instant.ofEpochMilli(currentRegistrationAttempt.getExpirationEpochMillis()).isBefore(clock.instant())) {
      throw new AttemptExpiredException();
    }

    final VerificationCodeSender sender = sendersByName.get(currentRegistrationAttempt.getSenderName());

    if (sender == null) {
      throw new IllegalArgumentException("Unrecognized sender: " + currentRegistrationAttempt.getSenderName());
    }

    boolean verified;

    try {
      verified = sender.checkVerificationCode(verificationCode,
          currentRegistrationAttempt.getSenderData().toByteArray());
    } catch (final SenderRateLimitedRequestException e) {
      throw new RateLimitExceededException(e.getRetryAfter(), session);
    } catch (final SenderRejectedRequestException e) {
      // The sender may view the submitted code as an illegal argument or may reject the attempt to check a code
      // altogether. We can treat any case of "the sender got it, but said 'no'" the same way we would treat an
      // accepted-but-incorrect code.
      verified = false;
    }

    return recordCheckVerificationCodeAttempt(session, verified ? verificationCode : null);
  }

  private RegistrationSession recordCheckVerificationCodeAttempt(final RegistrationSession session,
      @Nullable final String verifiedCode) throws SessionNotFoundException {

    return sessionRepository.updateSession(UUIDUtil.uuidFromByteString(session.getId()), s -> {
      final RegistrationSession.Builder builder = s.toBuilder()
          .setCheckCodeAttempts(s.getCheckCodeAttempts() + 1)
          .setLastCheckCodeAttemptEpochMillis(clock.millis());

      if (verifiedCode != null) {
        builder.setVerifiedCode(verifiedCode);
      }

      final Instant expiration = getSessionExpiration(builder.build());

      return builder.setExpirationEpochMillis(expiration.toEpochMilli())
          .build();
    });
  }

  /**
   * Interprets a raw {@code RegistrationSession} and produces {@link RegistrationSessionMetadata} suitable for
   * presentation to remote callers.
   *
   * @param session the session to interpret
   *
   * @return session metadata suitable for presentation to remote callers
   */
  public RegistrationSessionMetadata buildSessionMetadata(final RegistrationSession session) {
    final RegistrationSessionMetadata.Builder sessionMetadataBuilder = RegistrationSessionMetadata.newBuilder()
        .setSessionId(session.getId())
        .setE164(Long.parseLong(StringUtils.removeStart(session.getPhoneNumber(), "+")))
        .setVerified(StringUtils.isNotBlank(session.getVerifiedCode()))
        .setExpirationSeconds(Duration.between(clock.instant(), getSessionExpiration(session)).getSeconds());

    final NextActionTimes nextActionTimes = getNextActionTimes(session);
    final Instant currentTime = clock.instant();

    nextActionTimes.nextSms().ifPresent(nextAction -> {
      sessionMetadataBuilder.setMayRequestSms(true);
      sessionMetadataBuilder.setNextSmsSeconds(
          nextAction.isBefore(currentTime) ? 0 : Duration.between(currentTime, nextAction).toSeconds());
    });

    nextActionTimes.nextVoiceCall().ifPresent(nextAction -> {
      sessionMetadataBuilder.setMayRequestVoiceCall(true);
      sessionMetadataBuilder.setNextVoiceCallSeconds(
          nextAction.isBefore(currentTime) ? 0 : Duration.between(currentTime, nextAction).toSeconds());
    });

    nextActionTimes.nextCodeCheck().ifPresent(nextAction -> {
      sessionMetadataBuilder.setMayCheckCode(true);
      sessionMetadataBuilder.setNextCodeCheckSeconds(
          nextAction.isBefore(currentTime) ? 0 : Duration.between(currentTime, nextAction).toSeconds());
    });

    return sessionMetadataBuilder.build();
  }

  @VisibleForTesting
  Instant getSessionExpiration(final RegistrationSession session) {
    if (StringUtils.isNotBlank(session.getVerifiedCode())) {
      // The session must have been verified as a result of the last check
      return Instant.ofEpochMilli(session.getLastCheckCodeAttemptEpochMillis())
          .plus(SESSION_TTL_AFTER_LAST_ACTION);
    }

    final List<Instant> candidateExpirations = new ArrayList<>(session.getRegistrationAttemptsList().stream()
        .map(attempt -> Instant.ofEpochMilli(attempt.getExpirationEpochMillis()))
        .toList());

    final NextActionTimes nextActionTimes = getNextActionTimes(session);

    nextActionTimes.nextSms()
        .map(nextAction -> nextAction.plus(SESSION_TTL_AFTER_LAST_ACTION))
        .ifPresent(candidateExpirations::add);

    nextActionTimes.nextVoiceCall()
        .map(nextAction -> nextAction.plus(SESSION_TTL_AFTER_LAST_ACTION))
        .ifPresent(candidateExpirations::add);

    nextActionTimes.nextCodeCheck()
        .map(nextAction -> nextAction.plus(SESSION_TTL_AFTER_LAST_ACTION))
        .ifPresent(candidateExpirations::add);

    // If a session never has a successful registration attempt and exhausts all SMS and voice ratelimits,
    // fall back to the expiration set at session creation time
    return candidateExpirations.stream().max(Comparator.naturalOrder())
        .orElse(Instant.ofEpochMilli(session.getExpirationEpochMillis()));
  }

  @VisibleForTesting
  NextActionTimes getNextActionTimes(final RegistrationSession session) {
    // If the session is already verified, callers can't request or check more verification codes
    if (StringUtils.isNotBlank(session.getVerifiedCode())) {
      return NextActionTimes.EMPTY;
    }

    // Callers can't request more verification codes if they've exhausted their check attempts (since they can't check
    // any new codes they might receive)
    final Optional<Instant> nextSms = sendSmsVerificationCodePerSessionRateLimiter.getTimeOfNextAction(session);

    Optional<Instant> nextCodeCheck = Optional.empty();

    // Callers can only check codes if there's an active attempt
    if (session.getRegistrationAttemptsCount() > 0) {
      final Instant currentAttemptExpiration = Instant.ofEpochMilli(
          session.getRegistrationAttemptsList().get(session.getRegistrationAttemptsCount() - 1)
              .getExpirationEpochMillis());

      if (!clock.instant().isAfter(currentAttemptExpiration)) {
        nextCodeCheck = checkVerificationCodePerSessionRateLimiter.getTimeOfNextAction(session);
      }
    }

    // Callers may not request codes via phone call until they've attempted an SMS
    final boolean hasAttemptedSms = session.getRegistrationAttemptsList().stream().anyMatch(attempt ->
        attempt.getMessageTransport() == org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS) ||
        session.getRejectedTransportsList().contains(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS) ||
            session.getFailedAttemptsList().stream().anyMatch(attempt ->
                attempt.getMessageTransport() == org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS
                    && attempt.getFailedSendReason() != FailedSendReason.FAILED_SEND_REASON_SUSPECTED_FRAUD);

    final Optional<Instant> nextVoiceCall = hasAttemptedSms
        ? sendVoiceVerificationCodePerSessionRateLimiter.getTimeOfNextAction(session)
        : Optional.empty();

    return new NextActionTimes(nextSms, nextVoiceCall, nextCodeCheck);
  }

  private Phonenumber.PhoneNumber getPhoneNumberFromSession(final RegistrationSession session) {
    try {
      return PhoneNumberUtil.getInstance().parse(session.getPhoneNumber(), null);
    } catch (final NumberParseException e) {
      // This should never happen because we're parsing a phone number from the session, which means we've
      // parsed it successfully in the past
      throw new AssertionError("Previously-parsed phone number could not be parsed", e);
    }
  }
}
