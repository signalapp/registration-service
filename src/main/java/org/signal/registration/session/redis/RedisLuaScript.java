/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

/**
 * A {@code RedisLuaScript} manages a Lua script executed by Redis. It attempts to execute the script via the Redis
 * <a href="https://redis.io/commands/evalsha/">EVALSHA</a> command and will load the script via the Redis
 * <a href="https://redis.io/commands/script-load/">SCRIPT LOAD</a> command if the script is not loaded on the target
 * Redis server.
 */
class RedisLuaScript {

  private final byte[] script;
  private final ScriptOutputType outputType;

  private final String sha;

  /**
   * Constructs a new Redis Lua script with the given script and output type.
   *
   * @param script the Lua script to execute on a Redis server (generally a UTF-8 encoded string)
   * @param outputType the output type of the script
   */
  RedisLuaScript(final byte[] script, final ScriptOutputType outputType) {
    this.script = script;
    this.outputType = outputType;

    try {
      this.sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script));
    } catch (final NoSuchAlgorithmException e) {
      // This should never happen because all Java implementations are required to support SHA-1
      throw new AssertionError(e);
    }
  }

  /**
   * Executes this script with the given keys and arguments via the given Redis connection. This method will lazily load
   * the script on the target Redis server if it is not already present.
   *
   * @param connection the Redis connection via which to execute this script
   * @param keys the keys acted upon by this script
   * @param values the arguments to be passed to this script
   *
   * @return the output of the script
   *
   * @param <T> the expected return type of the script
   */
  <T> CompletableFuture<T> execute(final StatefulRedisConnection<byte[], byte[]> connection,
      final byte[][] keys,
      final byte[]... values) {

    //noinspection unchecked
    return (CompletableFuture<T>) connection.async().evalsha(sha, outputType, keys, values)
        .exceptionallyCompose(throwable -> {
          if (throwable instanceof RedisNoScriptException) {
            // The script may not have been loaded yet, or it may have been unloaded by external user intervention or
            // server failure
            return connection.async().scriptLoad(script)
                .thenCompose(sha -> connection.async().evalsha(sha, outputType, keys, values));
          } else {
            return CompletableFuture.failedFuture(throwable);
          }
        })
        .toCompletableFuture();
  }
}
