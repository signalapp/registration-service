/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.session.ConflictingUpdateException;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceTest {

  private RegistrationService registrationService;

  private VerificationCodeSender sender;
  private SessionRepository sessionRepository;
  private SessionRepository secondarySessionRepository;

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
    when(sessionRepository.updateSession(any(), any())).thenReturn(CompletableFuture.completedFuture(null));

    secondarySessionRepository = mock(SessionRepository.class);
    when(secondarySessionRepository.getSession(any()))
        .thenReturn(CompletableFuture.failedFuture(new SessionNotFoundException()));

    when(secondarySessionRepository.updateSession(any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new SessionNotFoundException()));

    final SenderSelectionStrategy senderSelectionStrategy = mock(SenderSelectionStrategy.class);
    when(senderSelectionStrategy.chooseVerificationCodeSender(any(), any(), any(), any())).thenReturn(sender);

    registrationService = new RegistrationService(senderSelectionStrategy, sessionRepository, List.of(sessionRepository,
        secondarySessionRepository), List.of(sender));
  }

  @Test
  void sendRegistrationCode() {
    when(sender.sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE))
        .thenReturn(CompletableFuture.completedFuture(VERIFICATION_CODE_BYTES));

    when(sessionRepository.createSession(eq(PHONE_NUMBER), eq(sender), any(), eq(VERIFICATION_CODE_BYTES)))
        .thenReturn(CompletableFuture.completedFuture(SESSION_ID));

    assertEquals(SESSION_ID,
        registrationService.sendRegistrationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE).join());

    verify(sender).sendVerificationCode(MessageTransport.SMS, PHONE_NUMBER, LANGUAGE_RANGES, CLIENT_TYPE);
    verify(sessionRepository).createSession(PHONE_NUMBER, sender, SESSION_TTL, VERIFICATION_CODE_BYTES);
  }

  @Test
  void checkRegistrationCode() {
    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(
            RegistrationSession.newBuilder()
                .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
                .setSenderName(SENDER_NAME)
                .setSessionData(ByteString.copyFromUtf8(VERIFICATION_CODE))
                .build()));

    when(sender.checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES))
        .thenReturn(CompletableFuture.completedFuture(true));

    assertTrue(registrationService.checkRegistrationCode(SESSION_ID, VERIFICATION_CODE).join());

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender).checkVerificationCode(VERIFICATION_CODE, VERIFICATION_CODE_BYTES);
    verify(sessionRepository).updateSession(eq(SESSION_ID), any());
  }

  @Test
  void checkRegistrationCodeSessionNotFound() {
    when(sessionRepository.getSession(any()))
        .thenReturn(CompletableFuture.failedFuture(new SessionNotFoundException()));

    assertFalse(registrationService.checkRegistrationCode(SESSION_ID, VERIFICATION_CODE).join());

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any());
  }

  @Test
  void checkRegistrationCodePreviouslyVerified() {
    when(sessionRepository.getSession(SESSION_ID))
        .thenReturn(CompletableFuture.completedFuture(
            RegistrationSession.newBuilder()
                .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
                .setSenderName(SENDER_NAME)
                .setSessionData(ByteString.copyFromUtf8(VERIFICATION_CODE))
                .setVerifiedCode(VERIFICATION_CODE)
                .build()));

    assertTrue(registrationService.checkRegistrationCode(SESSION_ID, VERIFICATION_CODE).join());

    verify(sessionRepository).getSession(SESSION_ID);
    verify(sender, never()).checkVerificationCode(any(), any());
    verify(sessionRepository, never()).updateSession(any(), any());
  }

  @Test
  void updateWithRetries() {
    when(sessionRepository.updateSession(any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new ConflictingUpdateException()))
        .thenReturn(CompletableFuture.completedFuture(null));

    final UUID sessionId = UUID.randomUUID();

    assertDoesNotThrow(
        () -> registrationService.updateSessionWithRetries(sessionId, session -> session, sessionRepository, 1).join());

    verify(sessionRepository, times(2)).updateSession(eq(sessionId), any());
  }

  @Test
  void updateWithRetriesNoConflict() {
    when(sessionRepository.updateSession(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(null));

    final UUID sessionId = UUID.randomUUID();

    assertDoesNotThrow(
        () -> registrationService.updateSessionWithRetries(sessionId, session -> session, sessionRepository, 1).join());

    verify(sessionRepository).updateSession(eq(sessionId), any());
  }

  @Test
  void updateWithRetriesExhausted() {
    when(sessionRepository.updateSession(any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new ConflictingUpdateException()));

    final UUID sessionId = UUID.randomUUID();
    final int retries = 3;

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.updateSessionWithRetries(sessionId, session -> session, sessionRepository, retries).join());

    assertTrue(completionException.getCause() instanceof ConflictingUpdateException);

    // We expect one initial attempt, then N retries on top of that
    verify(sessionRepository, times(retries + 1)).updateSession(eq(sessionId), any());
  }

  @Test
  void updateWithRetriesNonConflictException() {
    when(sessionRepository.updateSession(any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException()));

    final UUID sessionId = UUID.randomUUID();

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> registrationService.updateSessionWithRetries(sessionId, session -> session, sessionRepository, 1).join());

    assertTrue(completionException.getCause() instanceof IllegalArgumentException);

    verify(sessionRepository).updateSession(eq(sessionId), any());
  }
}
