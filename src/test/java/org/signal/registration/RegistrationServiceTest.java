/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import io.micronaut.context.event.ApplicationEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.rpc.RegistrationSessionMetadata;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.session.MemorySessionRepository;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import org.signal.registration.util.CompletionExceptions;
import org.signal.registration.util.UUIDUtil;

class RegistrationServiceTest {

  private RegistrationService registrationService;

  private VerificationCodeSender sender;
  private SessionRepository sessionRepository;
  private RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter;
  private RateLimiter<RegistrationSession> sendSmsVerificationCodeRateLimiter;
  private RateLimiter<RegistrationSession> sendVoiceVerificationCodeRateLimiter;
  private RateLimiter<RegistrationSession> checkVerificationCodeRateLimiter;

  private static final Phonenumber.PhoneNumber PHONE_NUMBER;
  private static final UUID SESSION_ID = UUID.randomUUID();
  private static final String SENDER_NAME = "mock-sender";
  private static final Duration SESSION_TTL = Duration.ofSeconds(17);
  private static final String VERIFICATION_CODE = "654321";
  private static final byte[] VERIFICATION_CODE_BYTES = VERIFICATION_CODE.getBytes(StandardCharsets.UTF_8);
  private static final List<Locale.LanguageRange> LANGUAGE_RANGES = Locale.LanguageRange.parse("en,de");
  private static final ClientType CLIENT_TYPE = ClientType.UNKNOWN;

  static {
    try {
      PHONE_NUMBER = PhoneNumberUtil.getInstance().parse("+12025550123", null);
    } catch (final NumberParseException e) {
      // This should never happen for a literally-specified, known-good number
      throw new AssertionError("Could not parse test phone number", e);
    }
  }

  @BeforeEach
  void setUp() {
    sender = mock(VerificationCodeSender.class);
    when(sender.getName()).thenReturn(SENDER_NAME);
    when(sender.getSessionTtl()).thenReturn(SESSION_TTL);

    sessionRepository = mock(SessionRepository.class);
    when(sessionRepository.updateSession(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));

    final SenderSelectionStrategy senderSelectionStrategy = mock(SenderSelectionStrategy.class);
    when(senderSelectionStrategy.chooseVerificationCodeSender(any(), any(), any(), any(), any())).thenReturn(sender);

    //noinspection unchecked
    sessionCreationRateLimiter = mock(RateLimiter.class);
    when(sessionCreationRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    //noinspection unchecked
    sendSmsVerificationCodeRateLimiter = mock(RateLimiter.class);
    when(sendSmsVerificationCodeRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    //noinspection unchecked
    sendVoiceVerificationCodeRateLimiter = mock(RateLimiter.class);
    when(sendVoiceVerificationCodeRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    //noinspection unchecked
    checkVerificationCodeRateLimiter = mock(RateLimiter.class);
    when(checkVerificationCodeRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    registrationService = new RegistrationService(senderSelectionStrategy,
        sessionRepository,
        sessionCreationRateLimiter,
        sendSmsVerificationCodeRateLimiter,
        sendVoiceVerificationCodeRateLimiter,
        checkVerificationCodeRateLimiter,
        List.of(sender),
        Clock.systemUTC());
  }

  @Test
  void createSession() {
    final RegistrationSession session = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(SESSION_ID))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .build();

    when(sessionRepository.createSession(eq(PHONE_NUMBER), any()))
        .thenReturn(CompletableFuture.completedFuture(session));

    assertEquals(session, registrationService.createRegistrationSession(PHONE_NUMBER).join());
  }

  @Test
  void createSessionRateLimited() {
    final RateLimitExceededException rateLimitExceededException = new RateLimitExceededException(Duration.ZERO);

    when(sessionCreationRateLimiter.checkRateLimit(any()))
        .thenReturn(CompletableFuture.failedFuture(rateLimitExceededException));

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.createRegistrationSession(PHONE_NUMBER).join());

    assertEquals(rateLimitExceededException, CompletionExceptions.unwrap(completionException));
    verify(sessionRepository, never()).createSession(any(), any());
  }

  @Test
  void sendVerificationCode() {
    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.completedFuture(VERIFICATION_CODE_BYTES));

    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(
            RegistrationSession.newBuilder()
                .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
                .build()));

    registrationService.sendVerificationCode(MessageTransport.SMS, SESSION_ID, null, LANGUAGE_RANGES, CLIENT_TYPE).join();

    verify(sender).sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE);
    verify(sendSmsVerificationCodeRateLimiter).checkRateLimit(any());
    verify(sendVoiceVerificationCodeRateLimiter, never()).checkRateLimit(any());
    verify(sessionRepository, never()).createSession(any(), any());
    verify(sessionRepository).updateSession(eq(SESSION_ID), any(), eq(SESSION_TTL));
  }

  @Test
  void sendVerificationCodeSmsRateLimited() {
    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(
            RegistrationSession.newBuilder()
                .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
                .build()));

    when(sendSmsVerificationCodeRateLimiter.checkRateLimit(any()))
        .thenReturn(CompletableFuture.failedFuture(new RateLimitExceededException(null, null)));

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.sendVerificationCode(MessageTransport.SMS, SESSION_ID, null, LANGUAGE_RANGES, CLIENT_TYPE).join());

    assertTrue(CompletionExceptions.unwrap(completionException) instanceof RateLimitExceededException);

    verify(sender, never()).sendVerificationCode(any(), any(), any(), any());
    verify(sendSmsVerificationCodeRateLimiter).checkRateLimit(any());
    verify(sendVoiceVerificationCodeRateLimiter, never()).checkRateLimit(any());
    verify(sessionRepository, never()).createSession(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any(), any());
  }

  @Test
  void sendVerificationCodeVoiceRateLimited() {
    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(
            RegistrationSession.newBuilder()
                .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
                .build()));

    when(sendVoiceVerificationCodeRateLimiter.checkRateLimit(any()))
        .thenReturn(CompletableFuture.failedFuture(new RateLimitExceededException(null, null)));

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.sendVerificationCode(MessageTransport.VOICE, SESSION_ID, null, LANGUAGE_RANGES, CLIENT_TYPE).join());

    assertTrue(CompletionExceptions.unwrap(completionException) instanceof RateLimitExceededException);

    verify(sender, never()).sendVerificationCode(any(), any(), any(), any());
    verify(sendSmsVerificationCodeRateLimiter, never()).checkRateLimit(any());
    verify(sendVoiceVerificationCodeRateLimiter).checkRateLimit(any());
    verify(sessionRepository, never()).createSession(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any(), any());
  }

  @Test
  void registrationAttempts() {
    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.completedFuture(VERIFICATION_CODE_BYTES));

    final Instant currentTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    final Clock clock = mock(Clock.class);
    when(clock.instant()).thenReturn(currentTime);
    when(clock.millis()).thenReturn(currentTime.toEpochMilli());

    @SuppressWarnings("unchecked") final MemorySessionRepository memorySessionRepository =
        new MemorySessionRepository(mock(ApplicationEventPublisher.class), clock);

    final SenderSelectionStrategy senderSelectionStrategy = mock(SenderSelectionStrategy.class);
    when(senderSelectionStrategy.chooseVerificationCodeSender(any(), any(), any(), any(), any())).thenReturn(sender);

    final RegistrationService registrationService =
        new RegistrationService(senderSelectionStrategy, memorySessionRepository, sessionCreationRateLimiter,
            sendSmsVerificationCodeRateLimiter, sendVoiceVerificationCodeRateLimiter, checkVerificationCodeRateLimiter,
            List.of(sender), clock);

    final String firstVerificationCode = "123456";
    final String secondVerificationCode = "234567";

    when(sender.sendVerificationCode(any(), eq(PHONE_NUMBER), eq(LANGUAGE_RANGES), eq(CLIENT_TYPE)))
        .thenReturn(CompletableFuture.completedFuture(firstVerificationCode.getBytes(StandardCharsets.UTF_8)))
        .thenReturn(CompletableFuture.completedFuture(secondVerificationCode.getBytes(StandardCharsets.UTF_8)));

    final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER).join();
    final UUID sessionId = UUIDUtil.uuidFromByteString(session.getId());

    registrationService.sendVerificationCode(MessageTransport.SMS, sessionId, null, LANGUAGE_RANGES, CLIENT_TYPE).join();

    {
      final RegistrationSession registrationSession = memorySessionRepository.getSession(sessionId).join();
      final ByteString expectedSessionData = ByteString.copyFromUtf8(firstVerificationCode);

      assertEquals(1, registrationSession.getRegistrationAttemptsList().size());

      final RegistrationAttempt firstAttempt = registrationSession.getRegistrationAttempts(0);
      assertEquals(sender.getName(), firstAttempt.getSenderName());
      assertEquals(currentTime.toEpochMilli(), firstAttempt.getTimestamp());
      assertEquals(expectedSessionData, firstAttempt.getSessionData());
      assertEquals(org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS, firstAttempt.getMessageTransport());
    }

    final Instant future = currentTime.plus(SESSION_TTL.dividedBy(2));
    when(clock.instant()).thenReturn(future);
    when(clock.millis()).thenReturn(future.toEpochMilli());

    registrationService.sendVerificationCode(MessageTransport.VOICE, sessionId, null, LANGUAGE_RANGES, CLIENT_TYPE).join();

    {
      final RegistrationSession registrationSession = memorySessionRepository.getSession(sessionId).join();
      final ByteString expectedSessionData = ByteString.copyFromUtf8(secondVerificationCode);

      assertEquals(2, registrationSession.getRegistrationAttemptsList().size());

      final RegistrationAttempt secondAttempt = registrationSession.getRegistrationAttempts(1);
      assertEquals(sender.getName(), secondAttempt.getSenderName());
      assertEquals(future.toEpochMilli(), secondAttempt.getTimestamp());
      assertEquals(expectedSessionData, secondAttempt.getSessionData());
      assertEquals(org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_VOICE, secondAttempt.getMessageTransport());
    }
  }

  @Test
  void checkVerificationCode() {
    final RegistrationSession session = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(SESSION_ID))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setSenderName(SENDER_NAME)
            .setSessionData(ByteString.copyFrom(VERIFICATION_CODE_BYTES))
            .build())
        .build();

    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(session));

    when(sender.checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES))
        .thenReturn(CompletableFuture.completedFuture(true));

    when(sessionRepository.updateSession(eq(SESSION_ID), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(RegistrationSession.newBuilder(session)
            .setVerifiedCode(VERIFICATION_CODE)
            .build()));

    assertEquals(VERIFICATION_CODE, registrationService.checkVerificationCode(SESSION_ID, VERIFICATION_CODE).join().getVerifiedCode());

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender).checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES);
    verify(sessionRepository).updateSession(eq(SESSION_ID), any(), isNull());
  }

  @Test
  void checkVerificationCodeSessionNotFound() {
    when(sessionRepository.getSession(any()))
        .thenReturn(CompletableFuture.failedFuture(new SessionNotFoundException()));

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.checkVerificationCode(SESSION_ID, VERIFICATION_CODE).join());

    assertTrue(CompletionExceptions.unwrap(completionException) instanceof SessionNotFoundException);

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any(), any());
  }

  @Test
  void checkVerificationCodePreviouslyVerified() {
    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(
            RegistrationSession.newBuilder()
                .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
                .setVerifiedCode(VERIFICATION_CODE)
                .build()));

    assertEquals(VERIFICATION_CODE, registrationService.checkVerificationCode(SESSION_ID, VERIFICATION_CODE).join().getVerifiedCode());

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any(), any());
  }

  @Test
  void checkVerificationCodeRateLimited() {
    final RegistrationSession session = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(SESSION_ID))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setSenderName(SENDER_NAME)
            .setSessionData(ByteString.copyFrom(VERIFICATION_CODE_BYTES))
            .build())
        .build();

    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(session));

    final Duration retryAfterDuration = Duration.ofMinutes(17);

    when(checkVerificationCodeRateLimiter.checkRateLimit(session))
        .thenReturn(CompletableFuture.failedFuture(new RateLimitExceededException(retryAfterDuration, session)));

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.checkVerificationCode(SESSION_ID, VERIFICATION_CODE).join());

    final RateLimitExceededException rateLimitExceededException =
        (RateLimitExceededException) CompletionExceptions.unwrap(completionException);

    assertEquals(Optional.of(session), rateLimitExceededException.getRegistrationSession());
    assertEquals(Optional.of(retryAfterDuration), rateLimitExceededException.getRetryAfterDuration());

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any(), any());
  }

  @Test
  void buildSessionMetadata() {
    final long nextSmsSeconds = 17;
    final long nextVoiceCallSeconds = 19;
    final long nextCodeCheckSeconds = 23;

    when(sendSmsVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofSeconds(nextSmsSeconds))));

    when(sendVoiceVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofSeconds(nextVoiceCallSeconds))));

    when(checkVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
        .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofSeconds(nextCodeCheckSeconds))));

    // Fresh session; unverified and no codes sent
    {
      final RegistrationSessionMetadata sessionMetadata =
          registrationService.buildSessionMetadata(getBaseSessionBuilder().build());

      assertEquals(UUIDUtil.uuidToByteString(SESSION_ID), sessionMetadata.getSessionId());
      assertEquals(
          Long.parseLong(StringUtils.removeStart(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164), "+")),
          sessionMetadata.getE164());

      assertFalse(sessionMetadata.getVerified());
      assertTrue(sessionMetadata.getMayRequestSms());
      assertEquals(nextSmsSeconds, sessionMetadata.getNextSmsSeconds());
      assertFalse(sessionMetadata.getMayRequestVoiceCall());
      assertEquals(0, sessionMetadata.getNextVoiceCallSeconds());
      assertFalse(sessionMetadata.getMayCheckCode());
      assertEquals(0, sessionMetadata.getNextCodeCheckSeconds());
    }

    // Unverified session with an initial SMS sent
    {
      final RegistrationSessionMetadata sessionMetadata =
          registrationService.buildSessionMetadata(getBaseSessionBuilder()
                  .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                      .setMessageTransport(org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS)
                      .build())
              .build());

      assertEquals(UUIDUtil.uuidToByteString(SESSION_ID), sessionMetadata.getSessionId());
      assertEquals(
          Long.parseLong(StringUtils.removeStart(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164), "+")),
          sessionMetadata.getE164());

      assertFalse(sessionMetadata.getVerified());
      assertTrue(sessionMetadata.getMayRequestSms());
      assertEquals(nextSmsSeconds, sessionMetadata.getNextSmsSeconds());
      assertTrue(sessionMetadata.getMayRequestVoiceCall());
      assertEquals(nextVoiceCallSeconds, sessionMetadata.getNextVoiceCallSeconds());
      assertTrue(sessionMetadata.getMayCheckCode());
      assertEquals(nextCodeCheckSeconds, sessionMetadata.getNextCodeCheckSeconds());
    }

    // Unverified session with SMS attempts exhausted
    {
      when(sendSmsVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
          .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

      final RegistrationSessionMetadata sessionMetadata =
          registrationService.buildSessionMetadata(getBaseSessionBuilder()
              .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                  .setMessageTransport(org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS)
                  .build())
              .build());

      assertEquals(UUIDUtil.uuidToByteString(SESSION_ID), sessionMetadata.getSessionId());
      assertEquals(
          Long.parseLong(StringUtils.removeStart(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164), "+")),
          sessionMetadata.getE164());

      assertFalse(sessionMetadata.getVerified());
      assertFalse(sessionMetadata.getMayRequestSms());
      assertEquals(0, sessionMetadata.getNextSmsSeconds());
      assertTrue(sessionMetadata.getMayRequestVoiceCall());
      assertEquals(nextVoiceCallSeconds, sessionMetadata.getNextVoiceCallSeconds());
      assertTrue(sessionMetadata.getMayCheckCode());
      assertEquals(nextCodeCheckSeconds, sessionMetadata.getNextCodeCheckSeconds());
    }

    // Unverified session with voice calls exhausted
    {
      when(sendSmsVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
          .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofSeconds(nextSmsSeconds))));

      when(sendVoiceVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
          .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

      final RegistrationSessionMetadata sessionMetadata =
          registrationService.buildSessionMetadata(getBaseSessionBuilder()
              .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                  .setMessageTransport(org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS)
                  .build())
              .build());

      assertEquals(UUIDUtil.uuidToByteString(SESSION_ID), sessionMetadata.getSessionId());
      assertEquals(
          Long.parseLong(StringUtils.removeStart(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164), "+")),
          sessionMetadata.getE164());

      assertFalse(sessionMetadata.getVerified());
      assertTrue(sessionMetadata.getMayRequestSms());
      assertEquals(nextSmsSeconds, sessionMetadata.getNextSmsSeconds());
      assertFalse(sessionMetadata.getMayRequestVoiceCall());
      assertEquals(0, sessionMetadata.getNextVoiceCallSeconds());
      assertTrue(sessionMetadata.getMayCheckCode());
      assertEquals(nextCodeCheckSeconds, sessionMetadata.getNextCodeCheckSeconds());
    }

    // Unverified session with code checks exhausted
    {
      when(sendSmsVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
          .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofSeconds(nextSmsSeconds))));

      when(sendVoiceVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
          .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofSeconds(nextVoiceCallSeconds))));

      when(checkVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
          .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

      final RegistrationSessionMetadata sessionMetadata =
          registrationService.buildSessionMetadata(getBaseSessionBuilder()
              .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                  .setMessageTransport(org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS)
                  .build())
              .build());

      assertEquals(UUIDUtil.uuidToByteString(SESSION_ID), sessionMetadata.getSessionId());
      assertEquals(
          Long.parseLong(StringUtils.removeStart(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164), "+")),
          sessionMetadata.getE164());

      assertFalse(sessionMetadata.getVerified());
      assertFalse(sessionMetadata.getMayRequestSms());
      assertEquals(0, sessionMetadata.getNextSmsSeconds());
      assertFalse(sessionMetadata.getMayRequestVoiceCall());
      assertEquals(0, sessionMetadata.getNextVoiceCallSeconds());
      assertFalse(sessionMetadata.getMayCheckCode());
      assertEquals(0, sessionMetadata.getNextCodeCheckSeconds());
    }

    // Verified session
    {
      when(sendSmsVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
          .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofSeconds(nextSmsSeconds))));

      when(sendVoiceVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
          .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofSeconds(nextVoiceCallSeconds))));

      when(checkVerificationCodeRateLimiter.getDurationUntilActionAllowed(any()))
          .thenReturn(CompletableFuture.completedFuture(Optional.of(Duration.ofSeconds(nextCodeCheckSeconds))));

      final RegistrationSessionMetadata sessionMetadata =
          registrationService.buildSessionMetadata(getBaseSessionBuilder()
              .addRegistrationAttempts(RegistrationAttempt.newBuilder()
                  .setMessageTransport(org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS)
                  .build())
              .setVerifiedCode("123456")
              .build());

      assertEquals(UUIDUtil.uuidToByteString(SESSION_ID), sessionMetadata.getSessionId());
      assertEquals(
          Long.parseLong(StringUtils.removeStart(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164), "+")),
          sessionMetadata.getE164());

      assertTrue(sessionMetadata.getVerified());
      assertFalse(sessionMetadata.getMayRequestSms());
      assertEquals(0, sessionMetadata.getNextSmsSeconds());
      assertFalse(sessionMetadata.getMayRequestVoiceCall());
      assertEquals(0, sessionMetadata.getNextVoiceCallSeconds());
      assertFalse(sessionMetadata.getMayCheckCode());
      assertEquals(0, sessionMetadata.getNextCodeCheckSeconds());
    }
  }

  private static RegistrationSession.Builder getBaseSessionBuilder() {
    return RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(SESSION_ID))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164));
  }

  @Test
  void checkVerificationCodeSenderException() {
    final RegistrationSession session = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(SESSION_ID))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setMessageTransport(org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setSenderName(SENDER_NAME)
            .setSessionData(ByteString.copyFrom(VERIFICATION_CODE_BYTES))
            .build())
        .build();

    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(session));

    when(sender.checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES))
        .thenReturn(CompletableFuture.failedFuture(new SenderRejectedRequestException(new RuntimeException("OH NO"))));

    when(sessionRepository.updateSession(eq(SESSION_ID), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(session));

    assertTrue(StringUtils.isBlank(
        registrationService.checkVerificationCode(SESSION_ID, VERIFICATION_CODE).join().getVerifiedCode()));

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender).checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES);
    verify(sessionRepository).updateSession(eq(SESSION_ID), any(), isNull());
  }
}
