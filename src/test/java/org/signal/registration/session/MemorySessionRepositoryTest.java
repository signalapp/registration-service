/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import io.micronaut.context.event.ApplicationEventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class MemorySessionRepositoryTest extends AbstractSessionRepositoryTest {

  private ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher;
  private Clock clock;

  @BeforeEach
  void setUp() {
    //noinspection unchecked
    sessionCompletedEventPublisher = mock(ApplicationEventPublisher.class);

    clock = mock(Clock.class);
    when(clock.instant()).thenAnswer((Answer<Instant>) invocationOnMock -> Clock.systemUTC().instant());
  }

  @Override
  protected MemorySessionRepository getRepository() {
    return new MemorySessionRepository(sessionCompletedEventPublisher, clock);
  }

  @Test
  void getSessionExpired() {
    final MemorySessionRepository repository = getRepository();

    final Instant now = Instant.now();
    when(clock.instant()).thenReturn(now);

    final UUID sessionId = repository.createSession(PHONE_NUMBER, TTL).join();
    final RegistrationSession expectedSession = RegistrationSession.newBuilder()
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .build();

    assertEquals(expectedSession, repository.getSession(sessionId).join());

    when(clock.instant()).thenReturn(now.plus(TTL).plus(Duration.ofSeconds(1)));

    final CompletionException completionException =
        assertThrows(CompletionException.class, () -> repository.getSession(sessionId).join());

    assertTrue(completionException.getCause() instanceof SessionNotFoundException);

    verify(sessionCompletedEventPublisher).publishEventAsync(new SessionCompletedEvent(expectedSession));
  }

  @Test
  void updateSessionExpired() {
    final MemorySessionRepository repository = getRepository();
    final String verificationCode = "123456";

    final Instant now = Instant.now();
    when(clock.instant()).thenReturn(now);

    final Function<RegistrationSession, RegistrationSession> setVerifiedCodeFunction =
        session -> session.toBuilder().setVerifiedCode(verificationCode).build();

    final UUID sessionId = repository.createSession(PHONE_NUMBER, TTL).join();
    repository.updateSession(sessionId, setVerifiedCodeFunction, null).join();

    final RegistrationSession expectedSession = RegistrationSession.newBuilder()
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .setVerifiedCode(verificationCode)
        .build();

    assertEquals(expectedSession, repository.getSession(sessionId).join());

    when(clock.instant()).thenReturn(now.plus(TTL).plus(Duration.ofSeconds(1)));

    final CompletionException completionException =
        assertThrows(CompletionException.class,
            () -> repository.updateSession(sessionId, setVerifiedCodeFunction, null).join());

    assertTrue(completionException.getCause() instanceof SessionNotFoundException);

    verify(sessionCompletedEventPublisher).publishEventAsync(new SessionCompletedEvent(expectedSession));
  }

  @Test
  void removeExpiredSessions() {
    final MemorySessionRepository repository = getRepository();

    assertEquals(0, repository.size());

    final Instant now = Instant.now();
    when(clock.instant()).thenReturn(now);

    repository.createSession(PHONE_NUMBER, TTL).join();

    assertEquals(1, repository.size());

    repository.removeExpiredSessions();

    assertEquals(1, repository.size(),
        "Sessions should not be removed before they have expired");

    when(clock.instant()).thenReturn(now.plus(TTL).plus(Duration.ofSeconds(1)));

    repository.removeExpiredSessions();
    assertEquals(0, repository.size(),
        "Sessions should be removed after they have expired");

    final SessionCompletedEvent expectedEvent = new SessionCompletedEvent(RegistrationSession.newBuilder()
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .build());

    verify(sessionCompletedEventPublisher).publishEventAsync(expectedEvent);
  }
}
