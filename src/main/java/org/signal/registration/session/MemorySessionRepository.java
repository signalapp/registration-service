/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.signal.registration.util.UUIDUtil;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.annotation.Nullable;

@Singleton
@Requires(env = {"dev", "test"})
@Requires(missingBeans = SessionRepository.class)
public class MemorySessionRepository implements SessionRepository {

  private final ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher;
  private final Clock clock;

  private final Map<UUID, RegistrationSessionAndExpiration> sessionsById = new ConcurrentHashMap<>();

  private record RegistrationSessionAndExpiration(RegistrationSession session, Instant expiration) {
  }

  public MemorySessionRepository(final ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher,
      final Clock clock) {

    this.sessionCompletedEventPublisher = sessionCompletedEventPublisher;
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

    expiredSessionIds.forEach(sessionId -> sessionCompletedEventPublisher.publishEventAsync(
        new SessionCompletedEvent(sessionsById.remove(sessionId).session())));
  }

  @Override
  public CompletableFuture<RegistrationSession> createSession(final Phonenumber.PhoneNumber phoneNumber,
      final Duration ttl) {

    final UUID sessionId = UUID.randomUUID();
    final RegistrationSession session = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(sessionId))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164))
        .build();

    sessionsById.put(sessionId, new RegistrationSessionAndExpiration(session, clock.instant().plus(ttl)));

    return CompletableFuture.completedFuture(session);
  }

  @Override
  public CompletableFuture<RegistrationSession> getSession(final UUID sessionId) {
    final RegistrationSessionAndExpiration sessionAndExpiration =
        sessionsById.computeIfPresent(sessionId, (id, existingSessionAndExpiration) -> {
          if (clock.instant().isAfter(existingSessionAndExpiration.expiration)) {
            sessionCompletedEventPublisher.publishEventAsync(
                new SessionCompletedEvent(existingSessionAndExpiration.session()));

            return null;
          } else {
            return existingSessionAndExpiration;
          }
        });

    return sessionAndExpiration != null ?
        CompletableFuture.completedFuture(sessionAndExpiration.session) :
        CompletableFuture.failedFuture(new SessionNotFoundException());
  }

  @Override
  public CompletableFuture<RegistrationSession> updateSession(final UUID sessionId,
      final Function<RegistrationSession, RegistrationSession> sessionUpdater,
      @Nullable final Duration ttl) {

    final RegistrationSessionAndExpiration updatedSessionAndExpiration =
        sessionsById.computeIfPresent(sessionId, (id, existingSessionAndExpiration) -> {
          if (clock.instant().isAfter(existingSessionAndExpiration.expiration())) {
            sessionCompletedEventPublisher.publishEventAsync(
                new SessionCompletedEvent(existingSessionAndExpiration.session()));

            return null;
          } else {
            final Instant updatedExpiration = ttl != null ?
                clock.instant().plus(ttl) :
                existingSessionAndExpiration.expiration();

            return new RegistrationSessionAndExpiration(
                sessionUpdater.apply(existingSessionAndExpiration.session()),
                updatedExpiration);
          }
        });

    return updatedSessionAndExpiration != null ?
        CompletableFuture.completedFuture(updatedSessionAndExpiration.session()) :
        CompletableFuture.failedFuture(new SessionNotFoundException());
  }

  @VisibleForTesting
  int size() {
    return sessionsById.size();
  }
}
