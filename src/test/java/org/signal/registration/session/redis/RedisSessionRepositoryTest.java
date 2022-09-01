/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.micronaut.core.io.socket.SocketUtils;
import org.junit.jupiter.api.*;
import org.signal.registration.session.AbstractSessionRepositoryTest;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import redis.embedded.RedisServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisSessionRepositoryTest extends AbstractSessionRepositoryTest {

  private static int redisPort;
  private static RedisServer redisServer;

  private RedisClient redisClient;
  private StatefulRedisConnection<byte[], byte[]> redisConnection;

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
  protected SessionRepository getRepository() {
    try {
      return new RedisSessionRepository(redisConnection, List.of(SENDER));
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
  void getSessionSenderNotFound() {
    final SessionRepository repository = getRepository();

    final UUID sessionId = repository.createSession(PHONE_NUMBER, SENDER, TTL, SESSION_DATA).join();

    redisConnection.sync().hset(RedisSessionRepository.getSessionKey(sessionId),
        RedisSessionRepository.KEY_SENDER_CLASS.getBytes(StandardCharsets.UTF_8),
        "not-a-real-sender-class".getBytes(StandardCharsets.UTF_8));

    final CompletionException completionException =
        assertThrows(CompletionException.class, () -> repository.getSession(sessionId).join());

    assertTrue(completionException.getCause() instanceof IllegalArgumentException);
  }
}
