/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.firestore;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.firestore.Firestore;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.context.event.ApplicationEventPublisher;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.session.AbstractSessionRepositoryTest;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import org.signal.registration.util.FirestoreTestUtil;
import org.signal.registration.util.UUIDUtil;
import org.testcontainers.containers.FirestoreEmulatorContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FirestoreSessionRepositoryTest extends AbstractSessionRepositoryTest {

  private Firestore firestore;
  private FirestoreSessionRepository repository;

  private ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher;

  private static final String PROJECT_ID = "firestore-session-repository-test";
  private static final Instant NOW = Instant.now();

  private static final String FIRESTORE_EMULATOR_IMAGE_NAME = "gcr.io/google.com/cloudsdktool/cloud-sdk:" +
      System.getProperty("firestore.emulator.version", "emulators");

  @Container
  private static final FirestoreEmulatorContainer CONTAINER = new FirestoreEmulatorContainer(
      DockerImageName.parse(FIRESTORE_EMULATOR_IMAGE_NAME));

  @BeforeEach
  protected void setUp() throws Exception {
    super.setUp();

    FirestoreTestUtil.clearFirestoreDatabase(CONTAINER, PROJECT_ID);

    final FirestoreSessionRepositoryConfiguration configuration =
        new FirestoreSessionRepositoryConfiguration("registration-sessions", "expiration", "remove-after");

    firestore = FirestoreTestUtil.buildFirestoreClient(CONTAINER, PROJECT_ID);

    //noinspection unchecked
    sessionCompletedEventPublisher = mock(ApplicationEventPublisher.class);

    when(getClock().instant()).thenReturn(NOW);

    repository =
        new FirestoreSessionRepository(firestore, Executors.newFixedThreadPool(2), new SimpleMeterRegistry(),
            sessionCompletedEventPublisher, configuration, getClock());
  }

  @AfterEach
  void tearDown() throws Exception {
    firestore.close();
  }

  @Override
  protected SessionRepository getRepository() {
    return repository;
  }

  @Test
  void getSessionExpired() {
    final UUID sessionId = UUIDUtil.uuidFromByteString(repository.createSession(PHONE_NUMBER, getClock().instant().plus(TTL)).join().getId());

    when(getClock().instant()).thenReturn(NOW.plus(TTL).plusMillis(1));

    final CompletionException completionException =
        assertThrows(CompletionException.class, () -> repository.getSession(sessionId).join());

    assertTrue(completionException.getCause() instanceof SessionNotFoundException);
  }

  @Test
  void removeExpiredSessions() {
    final Instant creation = getClock().instant();
    final Instant expiration = creation.plus(TTL);
    final UUID sessionId =
        UUIDUtil.uuidFromByteString(repository.createSession(PHONE_NUMBER, expiration).join().getId());

    assertDoesNotThrow(() -> repository.getSession(sessionId).join());

    repository.deleteExpiredSessions().join();

    when(getClock().instant()).thenReturn(NOW.plus(TTL).plusMillis(1));
    repository.deleteExpiredSessions().join();

    final CompletionException completionException =
        assertThrows(CompletionException.class, () -> repository.getSession(sessionId).join());

    assertTrue(completionException.getCause() instanceof SessionNotFoundException);

    final SessionCompletedEvent expectedEvent = new SessionCompletedEvent(RegistrationSession.newBuilder()
        .setId(UUIDUtil.uuidToByteString(sessionId))
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .setCreatedEpochMillis(creation.toEpochMilli())
        .setExpirationEpochMillis(expiration.toEpochMilli())
        .build());

    verify(sessionCompletedEventPublisher).publishEventAsync(expectedEvent);
  }
}
