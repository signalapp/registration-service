/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.firestore;

import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Blob;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import org.signal.registration.util.CompletionExceptions;
import org.signal.registration.util.FirestoreUtil;
import org.signal.registration.util.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Firestore session repository stores sessions in a Firestore collection. This repository stores each session in its
 * own Firestore "document" identified by the string form of the session's UUID. This repository will periodically query
 * for and discard expired sessions, but as a safety measure, it also expects that Firestore has a "garbage collection"
 * policy that will automatically remove stale sessions after some amount of time.
 */
@Requires(bean = Firestore.class)
@Primary
@Singleton
public class FirestoreSessionRepository implements SessionRepository {

  private final Firestore firestore;
  private final Executor executor;
  private final ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher;
  private final FirestoreSessionRepositoryConfiguration configuration;
  private final Clock clock;

  private final Timer createSessionTimer;
  private final Timer getSessionTimer;
  private final Timer updateSessionTimer;
  private final Timer deleteSessionTimer;

  private static final String SESSION_FIELD_NAME = "session";
  private static final Duration REMOVAL_TTL_PADDING = Duration.ofMinutes(5);

  private static final Logger logger = LoggerFactory.getLogger(FirestoreSessionRepository.class);

  public FirestoreSessionRepository(final Firestore firestore,
      @Named(TaskExecutors.IO) final Executor executor,
      final MeterRegistry meterRegistry,
      final ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher,
      final FirestoreSessionRepositoryConfiguration configuration,
      final Clock clock) {

    this.firestore = firestore;
    this.executor = executor;
    this.sessionCompletedEventPublisher = sessionCompletedEventPublisher;
    this.configuration = configuration;
    this.clock = clock;

    createSessionTimer = meterRegistry.timer(MetricsUtil.name(FirestoreSessionRepository.class, "createSession"));
    getSessionTimer = meterRegistry.timer(MetricsUtil.name(FirestoreSessionRepository.class, "getSession"));
    updateSessionTimer = meterRegistry.timer(MetricsUtil.name(FirestoreSessionRepository.class, "updateSession"));
    deleteSessionTimer = meterRegistry.timer(MetricsUtil.name(FirestoreSessionRepository.class, "deleteSession"));
  }

  @Scheduled(fixedDelay = "${firestore-session-repository.remove-expired-sessions-interval:10s}")
  @VisibleForTesting
  CompletableFuture<Void> deleteExpiredSessions() {
    final CollectionReference sessionCollection = firestore.collection(configuration.collectionName());

    final Query query = sessionCollection.whereLessThan(configuration.expirationFieldName(),
        FirestoreUtil.timestampFromInstant(clock.instant()));

    return FirestoreUtil.toCompletableFuture(query.get(), executor)
        .thenCompose(querySnapshot -> CompletableFuture.allOf(querySnapshot.getDocuments().stream()
            .map(this::deleteExpiredSession)
            .toList()
            .toArray(new CompletableFuture[0])));
  }

  CompletableFuture<Void> deleteExpiredSession(final QueryDocumentSnapshot queryDocumentSnapshot) {

    final Timer.Sample sample = Timer.start();

    return FirestoreUtil.toCompletableFuture(firestore.runAsyncTransaction(transaction -> {
          final DocumentReference documentReference =
              firestore.collection(configuration.collectionName()).document(queryDocumentSnapshot.getId());

          return ApiFutures.transform(transaction.get(documentReference), documentSnapshot -> {
            final Optional<RegistrationSession> maybeSession;

            if (documentSnapshot.exists() && documentSnapshot.get(SESSION_FIELD_NAME) instanceof Blob sessionBlob) {
              transaction.delete(documentReference);

              try {
                maybeSession = Optional.of(RegistrationSession.parseFrom(sessionBlob.toBytes()));
              } catch (final InvalidProtocolBufferException e) {
                throw new CompletionException(e);
              }
            } else {
              maybeSession = Optional.empty();
            }

            return maybeSession;
          }, executor);
        }), executor)
        .thenAccept(maybeSession -> maybeSession.ifPresent(session ->
            sessionCompletedEventPublisher.publishEventAsync(new SessionCompletedEvent(session))))
        .whenComplete((ignored, throwable) -> sample.stop(deleteSessionTimer));
  }

  @Override
  public CompletableFuture<RegistrationSession> createSession(final Phonenumber.PhoneNumber phoneNumber, final Duration ttl) {

    final Timer.Sample sample = Timer.start();

    final UUID sessionId = UUID.randomUUID();
    final RegistrationSession session = RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(sessionId))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164))
        .build();

    return FirestoreUtil.toCompletableFuture(firestore.collection(configuration.collectionName())
        .document(sessionId.toString())
        .set(Map.of(SESSION_FIELD_NAME, Blob.fromBytes(session.toByteArray()),
            configuration.expirationFieldName(), FirestoreUtil.timestampFromInstant(clock.instant().plus(ttl)),
            configuration.removalFieldName(), FirestoreUtil.timestampFromInstant(clock.instant().plus(ttl).plus(REMOVAL_TTL_PADDING)))),
            executor)
        .thenApply(ignored -> session)
        .whenComplete((id, throwable) -> sample.stop(createSessionTimer));
  }

  @Override
  public CompletableFuture<RegistrationSession> getSession(final UUID sessionId) {
    final Timer.Sample sample = Timer.start();

    return FirestoreUtil.toCompletableFuture(firestore.collection(configuration.collectionName()).document(sessionId.toString()).get(), executor)
        .thenApply(documentSnapshot -> {
          try {
            return extractSession(documentSnapshot);
          } catch (final SessionNotFoundException e) {
            throw new CompletionException(e);
          }
        })
        .whenComplete((session, throwable) -> {
          if (throwable != null && !(CompletionExceptions.unwrap(throwable) instanceof SessionNotFoundException)) {
            logger.warn("Unexpected exception when retrieving session", throwable);
          }

          sample.stop(getSessionTimer);
        });
  }

  @Override
  public CompletableFuture<RegistrationSession> updateSession(final UUID sessionId,
      final Function<RegistrationSession, RegistrationSession> sessionUpdater,
      final Function<RegistrationSession, Optional<Duration>> ttlFunction) {

    final Timer.Sample sample = Timer.start();

    return FirestoreUtil.toCompletableFuture(firestore.runAsyncTransaction(transaction -> {
          final DocumentReference documentReference =
              firestore.collection(configuration.collectionName()).document(sessionId.toString());

          return ApiFutures.transform(transaction.get(documentReference), documentSnapshot -> {
            final RegistrationSession updatedSession;

            try {
              updatedSession = sessionUpdater.apply(extractSession(documentSnapshot));
            } catch (final SessionNotFoundException e) {
              throw new CompletionException(e);
            }

            transaction.update(documentReference, SESSION_FIELD_NAME, Blob.fromBytes(updatedSession.toByteArray()));

            ttlFunction.apply(updatedSession).ifPresent(ttl -> transaction.update(documentReference, Map.of(
                configuration.expirationFieldName(), FirestoreUtil.timestampFromInstant(clock.instant().plus(ttl)),
                configuration.removalFieldName(), FirestoreUtil.timestampFromInstant(clock.instant().plus(ttl).plus(REMOVAL_TTL_PADDING))
            )));

            return updatedSession;
          }, executor);
        }), executor)
        .whenComplete((session, throwable) -> sample.stop(updateSessionTimer));
  }

  private RegistrationSession extractSession(final DocumentSnapshot documentSnapshot) throws SessionNotFoundException {
    if (documentSnapshot.exists() && documentSnapshot.get(SESSION_FIELD_NAME) instanceof Blob sessionBlob) {

      // It's possible that a stored session has expired, but hasn't been deleted yet
      if (documentSnapshot.get(configuration.expirationFieldName()) instanceof Timestamp sessionClosureTimestamp) {
        if (FirestoreUtil.instantFromTimestamp(sessionClosureTimestamp).isBefore(clock.instant())) {
          throw new SessionNotFoundException();
        }
      }

      try {
        return RegistrationSession.parseFrom(sessionBlob.toBytes());
      } catch (final InvalidProtocolBufferException e) {
        throw new UncheckedIOException(e);
      }
    } else {
      throw new SessionNotFoundException();
    }
  }
}
