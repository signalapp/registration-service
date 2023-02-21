/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.util.UUIDUtil;

public abstract class AbstractSessionRepositoryTest {

  private Clock clock;

  protected static final Phonenumber.PhoneNumber PHONE_NUMBER;

  static {
    try {
      PHONE_NUMBER = PhoneNumberUtil.getInstance().parse("+12025550123", null);
    } catch (final NumberParseException e) {
      // This should never happen for a literally-specified, known-good number
      throw new AssertionError("Could not parse test phone number", e);
    }
  }

  protected static final Duration TTL = Duration.ofMinutes(1);

  protected Clock getClock() {
    return clock;
  }

  protected abstract SessionRepository getRepository();

  @BeforeEach
  protected void setUp() throws Exception {
    clock = mock(Clock.class);
    when(clock.instant()).thenReturn(Instant.now());
  }

  @Test
  void createSession() {
    assertNotNull(getRepository().createSession(PHONE_NUMBER, clock.instant().plus(TTL)).join());
  }

  @Test
  void getSession() {
    final SessionRepository repository = getRepository();

    {
      final CompletionException completionException =
          assertThrows(CompletionException.class, () -> repository.getSession(UUID.randomUUID()).join());

      assertTrue(completionException.getCause() instanceof SessionNotFoundException);
    }

    {
      final RegistrationSession createdSession = repository.createSession(PHONE_NUMBER, clock.instant().plus(TTL)).join();
      final RegistrationSession expectedSession = RegistrationSession.newBuilder()
          .setId(createdSession.getId())
          .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
          .setExpirationEpochMillis(clock.instant().plus(TTL).toEpochMilli())
          .build();

      assertEquals(expectedSession, repository.getSession(UUIDUtil.uuidFromByteString(createdSession.getId())).join());
    }
  }

  @Test
  void updateSession() {
    final SessionRepository repository = getRepository();
    final String verificationCode = "123456";
    final Instant expiration = clock.instant().plus(TTL);
    final Instant expirationAfterUpdate = expiration.plusSeconds(17);

    final Function<RegistrationSession, RegistrationSession> updateVerifiedCodeFunction =
        session -> session.toBuilder()
            .setVerifiedCode(verificationCode)
            .setExpirationEpochMillis(expirationAfterUpdate.toEpochMilli())
            .build();

    {
      final CompletionException completionException =
          assertThrows(CompletionException.class,
              () -> repository.updateSession(UUID.randomUUID(), updateVerifiedCodeFunction).join());

      assertTrue(completionException.getCause() instanceof SessionNotFoundException);
    }

    {
      final RegistrationSession createdSession = repository.createSession(PHONE_NUMBER, expiration).join();
      final UUID sessionId = UUIDUtil.uuidFromByteString(createdSession.getId());

      final RegistrationSession updatedSession =
          repository.updateSession(sessionId, updateVerifiedCodeFunction).join();

      final RegistrationSession expectedSession = RegistrationSession.newBuilder()
          .setId(createdSession.getId())
          .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
          .setVerifiedCode(verificationCode)
          .setExpirationEpochMillis(expirationAfterUpdate.toEpochMilli())
          .build();

      assertEquals(expectedSession, updatedSession);
      assertEquals(expectedSession, repository.getSession(sessionId).join());
    }
  }
}
