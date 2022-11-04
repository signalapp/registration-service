/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import com.google.i18n.phonenumbers.Phonenumber;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * A session repository stores and retrieves data associated with registration sessions. Session repositories must also
 * make a best effort to publish a {@link SessionCompletedEvent} whenever a stored session is removed from the
 * repository when its TTL expires.
 *
 * @see io.micronaut.context.event.ApplicationEventPublisher
 */
public interface SessionRepository {

  /**
   * Asynchronously stores a new registration session.
   *
   * @param phoneNumber the phone number to be verified as part of this registration session
   * @param ttl the lifetime of this registration session
   * {@code sender} can use later to check verification codes for this session
   *
   * @return a future that yields the ID of the newly-created registration session after the session has been created
   * and stored
   */
  CompletableFuture<UUID> createSession(Phonenumber.PhoneNumber phoneNumber, Duration ttl);

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
   * Updates the session with the given identifier with the given session update function. Updates may fail if the
   * session is not found or if multiple processes try to apply conflicting updates to the same session at the same
   * time. In the case of a conflicting update, callers should generally retry the update operation.
   *
   * @param sessionId the identifier of the session to update
   * @param sessionUpdater a function that accepts an existing session and returns a new session with changes applied
   * @param ttl the updated TTL for this session; may be {@code null} in which case the existing TTL is unchanged
   *
   * @return a future that yields the updated session when the update has been applied and stored; may fail with a
   * {@link SessionNotFoundException} if no session is found for the given identifier
   */
  CompletableFuture<RegistrationSession> updateSession(UUID sessionId,
      Function<RegistrationSession, RegistrationSession> sessionUpdater,
      @Nullable Duration ttl);
}
