/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.CommandOutput;
import io.lettuce.core.protocol.AsyncCommand;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandType;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests resilience behavior for the Redis session repository. Resilience behavior is injected by Micronaut, and so this
 * must be a {@link MicronautTest}.
 */
@MicronautTest(startApplication = false)
// `redis-session-repository.uri` is required to activate the Redis bean configuration, but is overridden by a mock
// connection
@Property(name = "redis-session-repository.uri", value = "ignored")
@Property(name = "redis-session-repository.circuit-breaker.attempts", value = "3")
@Property(name = "redis-session-repository.circuit-breaker.delay", value = "10ms")
@Property(name = "redis-session-repository.circuit-breaker.reset", value = "10s")
class RedisSessionRepositoryResilienceTest {

  @MockBean(StatefulRedisConnection.class)
  StatefulRedisConnection<byte[], byte[]> mockConnection() {
    @SuppressWarnings("unchecked") final StatefulRedisConnection<byte[], byte[]> redisConnection =
        mock(StatefulRedisConnection.class);

    @SuppressWarnings("unchecked") final RedisCommands<byte[], byte[]> redisCommands = mock(RedisCommands.class);

    // We need to prescribe this behavior at mock creation time because beans will get created and the session
    // repository will try to load scripts before any of the test setup methods run.
    when(redisCommands.scriptLoad(any(byte[].class))).thenReturn("sha");
    when(redisConnection.sync()).thenReturn(redisCommands);

    return redisConnection;
  }

  private RedisAsyncCommands<byte[], byte[]> redisAsyncCommands;

  @Inject
  StatefulRedisConnection<byte[], byte[]> redisConnection;

  @Inject
  RedisSessionRepository redisSessionRepository;

  @BeforeEach
  void setUp() {
    //noinspection unchecked
    redisAsyncCommands = mock(RedisAsyncCommands.class);
    when(redisConnection.async()).thenReturn(redisAsyncCommands);
  }

  @Test
  void retry() {
    final AsyncCommand<byte[], byte[], byte[]> failedCommand =
        new AsyncCommand<>(new Command<>(CommandType.GET, new CommandOutput<>(ByteArrayCodec.INSTANCE, new byte[0]) {}));

    failedCommand.completeExceptionally(new RedisException("Unavailable"));

    final AsyncCommand<byte[], byte[], byte[]> successfulCommand =
        new AsyncCommand<>(new Command<>(CommandType.GET, new CommandOutput<>(ByteArrayCodec.INSTANCE, new byte[0]) {}));

    successfulCommand.complete();

    when(redisAsyncCommands.get(any()))
        .thenReturn(failedCommand)
        .thenReturn(successfulCommand);

    assertDoesNotThrow(() -> {});
  }

  @Test
  void circuitBreaker() {
    final AsyncCommand<byte[], byte[], byte[]> failedCommand =
        new AsyncCommand<>(new Command<>(CommandType.GET, new CommandOutput<>(ByteArrayCodec.INSTANCE, new byte[0]) {}));

    failedCommand.completeExceptionally(new RedisException("Unavailable"));

    final AsyncCommand<byte[], byte[], byte[]> successfulCommand =
        new AsyncCommand<>(new Command<>(CommandType.GET, new CommandOutput<>(ByteArrayCodec.INSTANCE, new byte[0]) {}));

    successfulCommand.complete();

    when(redisAsyncCommands.get(any())).thenReturn(failedCommand);

    {
      final CompletionException completionException = assertThrows(CompletionException.class, () ->
          redisSessionRepository.setSessionVerified(UUID.randomUUID(), "verification-code").join());

      assertTrue(completionException.getCause() instanceof RedisException);
    }

    // At this point, the breaker should be tripped
    when(redisAsyncCommands.get(any())).thenReturn(successfulCommand);

    {
      final CompletionException completionException = assertThrows(CompletionException.class, () ->
          redisSessionRepository.setSessionVerified(UUID.randomUUID(), "verification-code").join());

      assertTrue(completionException.getCause() instanceof RedisException);
    }
  }
}
