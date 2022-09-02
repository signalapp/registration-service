/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.protobuf.ByteString;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.core.io.socket.SocketUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.session.AbstractSessionRepositoryTest;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import redis.embedded.RedisServer;

class RedisSessionRepositoryTest extends AbstractSessionRepositoryTest {

  private static int redisPort;
  private static RedisServer redisServer;

  private RedisClient redisClient;
  private StatefulRedisConnection<byte[], byte[]> redisConnection;

  private ApplicationEventPublisher<SessionCompletedEvent> sessionCompletedEventPublisher;
  private Clock clock;

  private static final Instant NOW = Instant.now();

  @BeforeAll
  static void setUpBeforeAll() {
    redisPort = SocketUtils.findAvailableTcpPort();

    redisServer = RedisServer.builder()
        .setting("appendonly no")
        .setting("save \"\"")
        .port(redisPort)
        .build();

    redisServer.start();
  }

  @BeforeEach
  void setUp() {
    redisClient = RedisClient.create("redis://localhost:" + redisPort);
    redisConnection = redisClient.connect(new ByteArrayCodec());

    redisConnection.sync().flushall();

    //noinspection unchecked
    sessionCompletedEventPublisher = mock(ApplicationEventPublisher.class);
    clock = mock(Clock.class);

    when(clock.instant()).thenReturn(NOW);
    when(clock.millis()).thenReturn(NOW.toEpochMilli());
  }

  @AfterEach
  void tearDown() {
    redisConnection.close();
    redisClient.close();
  }

  @AfterAll
  static void tearDownAfterAll() {
    redisServer.stop();
  }

  @Override
  protected RedisSessionRepository getRepository() {
    try {
      return new RedisSessionRepository(redisConnection, sessionCompletedEventPublisher, clock);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void getSessionExpired() {
    final SessionRepository repository = getRepository();

    final UUID sessionId = repository.createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join();

    // Expire the session immediately
    redisConnection.sync().expire(RedisSessionRepository.getSessionKey(sessionId), 0);

    final CompletionException completionException =
        assertThrows(CompletionException.class, () -> repository.getSession(sessionId).join());

    assertTrue(completionException.getCause() instanceof SessionNotFoundException);
  }

  @Test
  void removeExpiredSessions() {
    final RedisSessionRepository repository = getRepository();

    final UUID sessionId = repository.createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join();

    assertNotNull(redisConnection.sync()
        .zscore(RedisSessionRepository.EXPIRATION_QUEUE_KEY, RedisSessionRepository.getSessionKey(sessionId)));

    assertEquals(0, repository.removeExpiredSessions().block());

    final Instant expiration = NOW.plus(TTL).plusMillis(1);
    when(clock.instant()).thenReturn(expiration);
    when(clock.millis()).thenReturn(expiration.toEpochMilli());

    assertEquals(1, repository.removeExpiredSessions().block());

    assertNull(redisConnection.sync()
        .zscore(RedisSessionRepository.EXPIRATION_QUEUE_KEY, RedisSessionRepository.getSessionKey(sessionId)));

    final CompletionException completionException =
        assertThrows(CompletionException.class, () -> repository.getSession(sessionId).join());

    assertTrue(completionException.getCause() instanceof SessionNotFoundException);

    final SessionCompletedEvent expectedEvent = new SessionCompletedEvent(RegistrationSession.newBuilder()
        .setPhoneNumber(PhoneNumberUtil.getInstance().format(PHONE_NUMBER, PhoneNumberUtil.PhoneNumberFormat.E164))
        .setSenderName(SENDER.getName())
        .setSessionData(ByteString.copyFrom(SESSION_DATA))
        .build());

    verify(sessionCompletedEventPublisher).publishEventAsync(expectedEvent);
  }

  @Test
  void removeExpiredSessionsSessionGone() {
    final RedisSessionRepository repository = getRepository();

    final UUID sessionId = repository.createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join();

    final Instant expiration = NOW.plus(TTL).plusMillis(1);
    when(clock.instant()).thenReturn(expiration);
    when(clock.millis()).thenReturn(expiration.toEpochMilli());

    // Artificially remove the session to simulate a "concurrent workers" case
    redisConnection.sync().del(RedisSessionRepository.getSessionKey(sessionId));

    assertEquals(0, repository.removeExpiredSessions().block());

    assertNull(redisConnection.sync()
        .zscore(RedisSessionRepository.EXPIRATION_QUEUE_KEY, RedisSessionRepository.getSessionKey(sessionId)));

    verify(sessionCompletedEventPublisher, never()).publishEventAsync(any());
  }

  @Test
  void removeExpiredSessionsQueueEntryGone() {
    final RedisSessionRepository repository = getRepository();

    final UUID sessionId = repository.createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join();

    final Instant expiration = NOW.plus(TTL).plusMillis(1);
    when(clock.instant()).thenReturn(expiration);
    when(clock.millis()).thenReturn(expiration.toEpochMilli());

    // Artificially remove the session key from the queue to simulate a "concurrent workers" case
    redisConnection.sync().zrem(RedisSessionRepository.EXPIRATION_QUEUE_KEY,
        RedisSessionRepository.getSessionKey(sessionId));

    assertEquals(0, repository.removeExpiredSessions().block());

    assertNull(redisConnection.sync()
        .zscore(RedisSessionRepository.EXPIRATION_QUEUE_KEY, RedisSessionRepository.getSessionKey(sessionId)));

    verify(sessionCompletedEventPublisher, never()).publishEventAsync(any());
  }
}
