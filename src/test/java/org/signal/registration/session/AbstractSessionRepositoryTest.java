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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.VerificationCodeSender;

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

  protected static final VerificationCodeSender SENDER = new NoopVerificationCodeSender();
  protected static final Duration TTL = Duration.ofMinutes(1);
  protected static final byte[] SESSION_DATA = "session-data".getBytes(StandardCharsets.UTF_8);

  private static class NoopVerificationCodeSender implements VerificationCodeSender {

    @Override
    public String getName() {
      return "noop";
    }

    @Override
    public Duration getSessionTtl() {
      return Duration.ZERO;
    }

    @Override
    public boolean supportsDestination(final MessageTransport messageTransport,
        final Phonenumber.PhoneNumber phoneNumber,
        final List<Locale.LanguageRange> languageRanges,
        final ClientType clientType) {

      return false;
    }

    @Override
    public CompletableFuture<byte[]> sendVerificationCode(final MessageTransport messageTransport,
        final Phonenumber.PhoneNumber phoneNumber,
        final List<Locale.LanguageRange> languageRanges,
        final ClientType clientType) {

      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }

    @Override
    public CompletableFuture<Boolean> checkVerificationCode(final String verificationCode, final byte[] sessionData) {
      return CompletableFuture.failedFuture(new UnsupportedOperationException());
    }
  }

  protected abstract SessionRepository getRepository();

  @Test
  void createSession() {
    assertNotNull(getRepository().createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join());
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
      final UUID sessionId = repository.createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join();
      final RegistrationSession expectedSession = RegistrationSession.newBuilder()
          .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
          .setSenderName(SENDER.getName())
          .setSessionData(ByteString.copyFrom(SESSION_DATA))
          .build();

      assertEquals(expectedSession, repository.getSession(sessionId).join());
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
              () -> repository.updateSession(UUID.randomUUID(), updateVerifiedCodeFunction).join());

      assertTrue(completionException.getCause() instanceof SessionNotFoundException);
    }

    {
      final UUID sessionId = repository.createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join();
      final RegistrationSession updatedSession = repository.updateSession(sessionId, updateVerifiedCodeFunction).join();

      final RegistrationSession expectedSession = RegistrationSession.newBuilder()
          .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
          .setSenderName(SENDER.getName())
          .setSessionData(ByteString.copyFrom(SESSION_DATA))
          .setVerifiedCode(verificationCode)
          .build();

      assertEquals(expectedSession, updatedSession);
      assertEquals(expectedSession, repository.getSession(sessionId).join());
    }
  }
}
