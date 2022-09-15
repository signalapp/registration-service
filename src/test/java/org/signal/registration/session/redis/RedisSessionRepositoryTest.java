/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.micronaut.core.io.socket.SocketUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.session.AbstractSessionRepositoryTest;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.session.SessionRepository;
import redis.embedded.RedisServer;

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
      return new RedisSessionRepository(redisConnection);
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
}
