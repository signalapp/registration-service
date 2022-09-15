/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class MemorySessionRepositoryTest extends AbstractSessionRepositoryTest {

  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = mock(Clock.class);
    when(clock.instant()).thenAnswer((Answer<Instant>) invocationOnMock -> Clock.systemUTC().instant());
  }

  @Override
  protected MemorySessionRepository getRepository() {
    return new MemorySessionRepository(clock);
  }

  @Test
  void getSessionExpired() {
    final MemorySessionRepository repository = getRepository();

    final Instant now = Instant.now();
    when(clock.instant()).thenReturn(now);

    final UUID sessionId = repository.createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join();
    final RegistrationSession expectedSession = RegistrationSession.newBuilder()
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .setSenderCanonicalClassName(SENDER.getClass().getCanonicalName())
        .setSessionData(ByteString.copyFrom(SESSION_DATA))
        .build();

    assertEquals(expectedSession, repository.getSession(sessionId).join());

    when(clock.instant()).thenReturn(now.plus(TTL).plus(Duration.ofSeconds(1)));

    final CompletionException completionException =
        assertThrows(CompletionException.class, () -> repository.getSession(UUID.randomUUID()).join());

    assertTrue(completionException.getCause() instanceof SessionNotFoundException);
  }

  @Test
  void setSessionVerifiedExpired() {
    final MemorySessionRepository repository = getRepository();
    final String verificationCode = "123456";

    final Instant now = Instant.now();
    when(clock.instant()).thenReturn(now);

    final UUID sessionId = repository.createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join();
    repository.setSessionVerified(sessionId, verificationCode).join();

    final RegistrationSession expectedSession = RegistrationSession.newBuilder()
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .setSenderCanonicalClassName(SENDER.getClass().getCanonicalName())
        .setSessionData(ByteString.copyFrom(SESSION_DATA))
        .setVerifiedCode(verificationCode)
        .build();;

    assertEquals(expectedSession, repository.getSession(sessionId).join());

    when(clock.instant()).thenReturn(now.plus(TTL).plus(Duration.ofSeconds(1)));

    final CompletionException completionException =
        assertThrows(CompletionException.class,
            () -> repository.setSessionVerified(UUID.randomUUID(), verificationCode).join());

    assertTrue(completionException.getCause() instanceof SessionNotFoundException);
  }

  @Test
  void removeExpiredSessions() {
    final MemorySessionRepository repository = getRepository();

    assertEquals(0, repository.size());

    final Instant now = Instant.now();
    when(clock.instant()).thenReturn(now);

    repository.createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join();

    assertEquals(1, repository.size());

    repository.removeExpiredSessions();

    assertEquals(1, repository.size(),
        "Sessions should not be removed before they have expired");

    when(clock.instant()).thenReturn(now.plus(TTL).plus(Duration.ofSeconds(1)));

    repository.removeExpiredSessions();
    assertEquals(0, repository.size(),
        "Sessions should be removed after they have expired");
  }
}
