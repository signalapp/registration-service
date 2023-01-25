/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.signal.registration.util.UUIDUtil;

public abstract class AbstractSessionRepositoryTest {

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

  protected abstract SessionRepository getRepository();

  @Test
  void createSession() {
    assertNotNull(getRepository().createSession(PHONE_NUMBER, TTL).join());
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
      final RegistrationSession createdSession = repository.createSession(PHONE_NUMBER, TTL).join();
      final RegistrationSession expectedSession = RegistrationSession.newBuilder()
          .setId(createdSession.getId())
          .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
          .build();

      assertEquals(expectedSession, repository.getSession(UUIDUtil.uuidFromByteString(createdSession.getId())).join());
    }
  }

  @Test
  void updateSession() {
    final SessionRepository repository = getRepository();
    final String verificationCode = "123456";

    final Function<RegistrationSession, RegistrationSession> updateVerifiedCodeFunction =
        session -> session.toBuilder().setVerifiedCode(verificationCode).build();

    {
      final CompletionException completionException =
          assertThrows(CompletionException.class,
              () -> repository.updateSession(UUID.randomUUID(), updateVerifiedCodeFunction, null).join());

      assertTrue(completionException.getCause() instanceof SessionNotFoundException);
    }

    {
      final RegistrationSession createdSession = repository.createSession(PHONE_NUMBER, TTL).join();
      final UUID sessionId = UUIDUtil.uuidFromByteString(createdSession.getId());

      final RegistrationSession updatedSession = repository.updateSession(sessionId, updateVerifiedCodeFunction, null).join();

      final RegistrationSession expectedSession = RegistrationSession.newBuilder()
          .setId(createdSession.getId())
          .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
          .setVerifiedCode(verificationCode)
          .build();

      assertEquals(expectedSession, updatedSession);
      assertEquals(expectedSession, repository.getSession(sessionId).join());
    }
  }
}
