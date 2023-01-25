/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
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

    registrationService = new RegistrationService(senderSelectionStrategy,
        sessionRepository,
        sessionCreationRateLimiter,
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
  void sendRegistrationCode() {
    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.completedFuture(VERIFICATION_CODE_BYTES));

    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(
            RegistrationSession.newBuilder()
                .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
                .build()));

    registrationService.sendRegistrationCode(MessageTransport.SMS, SESSION_ID, null, LANGUAGE_RANGES, CLIENT_TYPE).join();

    verify(sender).sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE);
    verify(sessionRepository, never()).createSession(any(), any());
    verify(sessionRepository).updateSession(eq(SESSION_ID), any(), eq(SESSION_TTL));
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
        new RegistrationService(senderSelectionStrategy, memorySessionRepository, sessionCreationRateLimiter, List.of(sender), clock);

    final String firstVerificationCode = "123456";
    final String secondVerificationCode = "234567";

    when(sender.sendVerificationCode(any(), eq(PHONE_NUMBER), eq(LANGUAGE_RANGES), eq(CLIENT_TYPE)))
        .thenReturn(CompletableFuture.completedFuture(firstVerificationCode.getBytes(StandardCharsets.UTF_8)))
        .thenReturn(CompletableFuture.completedFuture(secondVerificationCode.getBytes(StandardCharsets.UTF_8)));

    final RegistrationSession session = registrationService.createRegistrationSession(PHONE_NUMBER).join();
    final UUID sessionId = UUIDUtil.uuidFromByteString(session.getId());

    registrationService.sendRegistrationCode(MessageTransport.SMS, sessionId, null, LANGUAGE_RANGES, CLIENT_TYPE).join();

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

    registrationService.sendRegistrationCode(MessageTransport.VOICE, sessionId, null, LANGUAGE_RANGES, CLIENT_TYPE).join();

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
  void checkRegistrationCode() {
    final RegistrationSession session = RegistrationSession.newBuilder()
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

    assertEquals(VERIFICATION_CODE, registrationService.checkRegistrationCode(SESSION_ID, VERIFICATION_CODE).join().getVerifiedCode());

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender).checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES);
    verify(sessionRepository).updateSession(eq(SESSION_ID), any(), isNull());
  }

  @Test
  void checkRegistrationCodeSessionNotFound() {
    when(sessionRepository.getSession(any()))
        .thenReturn(CompletableFuture.failedFuture(new SessionNotFoundException()));

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.checkRegistrationCode(SESSION_ID, VERIFICATION_CODE).join());

    assertTrue(CompletionExceptions.unwrap(completionException) instanceof SessionNotFoundException);

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any(), any());
  }

  @Test
  void checkRegistrationCodePreviouslyVerified() {
    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(
            RegistrationSession.newBuilder()
                .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
                .setVerifiedCode(VERIFICATION_CODE)
                .build()));

    assertEquals(VERIFICATION_CODE, registrationService.checkRegistrationCode(SESSION_ID, VERIFICATION_CODE).join().getVerifiedCode());

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any(), any());
  }
}
