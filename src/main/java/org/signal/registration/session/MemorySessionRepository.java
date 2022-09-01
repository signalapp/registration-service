/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.signal.registration.sender.VerificationCodeSender;

@Singleton
@Requires(env = {"dev", "test"})
@Requires(missingBeans = SessionRepository.class)
public class MemorySessionRepository implements SessionRepository {

  private final Clock clock;

  private final Map<UUID, RegistrationSessionAndExpiration> sessionsById = new ConcurrentHashMap<>();

  private record RegistrationSessionAndExpiration(RegistrationSession session, Instant expiration) {
  }

  public MemorySessionRepository(final Clock clock) {
    this.clock = clock;
  }

  @Scheduled(fixedRate = "10s")
  @VisibleForTesting
  void removeExpiredSessions() {
    final Instant now = clock.instant();

    final List<UUID> expiredSessionIds = sessionsById.entrySet().stream()
        .filter(entry -> now.isAfter(entry.getValue().expiration()))
        .map(Map.Entry::getKey)
        .toList();

    expiredSessionIds.forEach(sessionsById::remove);
  }

  @Override
  public CompletableFuture<UUID> createSession(final Phonenumber.PhoneNumber phoneNumber,
      final VerificationCodeSender sender,
      final Duration ttl,
      final byte[] sessionData) {

    final UUID sessionId = UUID.randomUUID();

    sessionsById.put(sessionId, new RegistrationSessionAndExpiration(
        new RegistrationSession(phoneNumber, sender, sessionData, null),
        clock.instant().plus(ttl)));

    return CompletableFuture.completedFuture(sessionId);
  }

  @Override
  public CompletableFuture<RegistrationSession> getSession(final UUID sessionId) {
    final RegistrationSessionAndExpiration sessionAndExpiration =
        sessionsById.computeIfPresent(sessionId, (id, existingSessionAndExpiration) ->
            clock.instant().isAfter(existingSessionAndExpiration.expiration()) ? null : existingSessionAndExpiration);

    return sessionAndExpiration != null ?
        CompletableFuture.completedFuture(sessionAndExpiration.session) :
        CompletableFuture.failedFuture(new SessionNotFoundException());
  }

  @Override
  public CompletableFuture<Void> setSessionVerified(final UUID sessionId, final String verificationCode) {
    final RegistrationSessionAndExpiration updatedSessionAndExpiration =
        sessionsById.computeIfPresent(sessionId, (id, existingSessionAndExpiration) ->
            clock.instant().isAfter(existingSessionAndExpiration.expiration()) ?
                null :
                new RegistrationSessionAndExpiration(
                    new RegistrationSession(existingSessionAndExpiration.session().phoneNumber(),
                        existingSessionAndExpiration.session().sender(),
                        existingSessionAndExpiration.session().sessionData(),
                        verificationCode), existingSessionAndExpiration.expiration()));

    return updatedSessionAndExpiration != null ?
        CompletableFuture.completedFuture(null) : CompletableFuture.failedFuture(new SessionNotFoundException());
  }

  @VisibleForTesting
  int size() {
    return sessionsById.size();
  }
}
