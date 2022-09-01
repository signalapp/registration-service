/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import com.google.i18n.phonenumbers.Phonenumber;
import org.signal.registration.sender.VerificationCodeSender;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * A session repository stores and retrieves data associated with registration sessions.
 */
public interface SessionRepository {

  /**
   * Asynchronously stores a new registration session.
   *
   * @param phoneNumber the phone number to be verified as part of this registration session
   * @param sender the verification code sender to be used to send and check verification codes
   * @param ttl the lifetime of this registration session
   * @param sessionData an opaque sequence of bytes (often a registration code or upstream session identifier) the given
   * {@code sender} can use later to check verification codes for this session
   *
   * @return a future that yields the ID of the newly-created registration session after the session has been created
   * and stored
   */
  CompletableFuture<UUID> createSession(Phonenumber.PhoneNumber phoneNumber,
      VerificationCodeSender sender,
      Duration ttl,
      byte[] sessionData);

  /**
   * Returns the registration session associated with the given session identifier.
   *
   * @param sessionId the identifier of the session to retrieve
   *
   * @return a future that yields the session stored with the given session identifier or fails with a
   * {@link SessionNotFoundException} if no session was found for the given identifier
   */
  CompletableFuture<RegistrationSession> getSession(UUID sessionId);

  /**
   * Marks the given session as successfully verified with the given verification code. Callers may store known-valid
   * verification codes to prevent repeated upstream verification calls if, for example, a request is retried after a
   * connection closes unexpectedly.
   *
   * @param sessionId the identifier of the session to update
   * @param verificationCode the valid verification code for this session
   *
   * @return a future that completes when the underlying session record has been updated
   */
  CompletableFuture<Void> setSessionVerified(UUID sessionId, String verificationCode);
}
