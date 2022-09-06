/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.micronaut.core.io.socket.SocketUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.embedded.RedisServer;

class RedisLuaScriptTest {

  private static int redisPort;
  private static RedisServer redisServer;

  private static final String SCRIPT = """
      local key = KEYS[1]
      local addend = ARGV[1]

      redis.call("INCRBY", key, addend);
      """;

  private RedisClient redisClient;
  private StatefulRedisConnection<byte[], byte[]> redisConnection;

  private RedisLuaScript redisLuaScript;

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

    redisLuaScript = new RedisLuaScript(SCRIPT.getBytes(StandardCharsets.UTF_8), ScriptOutputType.VALUE);
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

  @Test
  void execute() {
    final byte[] key = "x".getBytes(StandardCharsets.UTF_8);
    int expectedSum = 0;

    for (int i = 7; i < 13; i++) {
      redisLuaScript.execute(redisConnection,
          new byte[][]{key},
          String.valueOf(i).getBytes(StandardCharsets.UTF_8)).join();

      expectedSum += i;
    }

    assertEquals(expectedSum,
        Integer.parseInt(new String(redisConnection.sync().get(key), StandardCharsets.UTF_8)));

    final Map<String, Map<String, Integer>> commandStats = getCommandStats();

    assertEquals(1, commandStats.get("script").get("calls"),
        "Script should be loaded exactly once");

    assertEquals(1, commandStats.get("evalsha").get("failed_calls"),
        "Script should have exactly one tried-and-failed evaluation");

    assertTrue(commandStats.get("evalsha").get("calls") - commandStats.get("evalsha").get("failed_calls") > 1,
        "Script should be executed successfully multiple times");
  }

  private Map<String, Map<String, Integer>> getCommandStats() {
    // An example commandstats block:
    //
    // ```
    // # Commandstats
    // cmdstat_get:calls=1,usec=1,usec_per_call=1.00,rejected_calls=0,failed_calls=0
    // cmdstat_script:calls=1,usec=26,usec_per_call=26.00,rejected_calls=0,failed_calls=0
    // cmdstat_incrby:calls=6,usec=29,usec_per_call=4.83,rejected_calls=0,failed_calls=0
    // cmdstat_hello:calls=1,usec=1,usec_per_call=1.00,rejected_calls=0,failed_calls=0
    // cmdstat_evalsha:calls=7,usec=53,usec_per_call=7.57,rejected_calls=0,failed_calls=1
    // ```
    final String commandStats = redisConnection.sync().info("commandstats");

    return commandStats.lines()
        .filter(line -> !line.startsWith("#"))
        .collect(Collectors.toMap(
            line -> line.substring("cmdstat_".length(), line.indexOf(':')),
            RedisLuaScriptTest::parseCommandCounts
        ));
  }

  private static Map<String, Integer> parseCommandCounts(final String line) {
    // Example line:
    //
    // ```
    // cmdstat_get:calls=1,usec=1,usec_per_call=1.00,rejected_calls=0,failed_calls=0
    // ```
    return Arrays.stream(line.substring(line.indexOf(':') + 1).split(","))
        // Hack: ignore floating point values; we're only interested in integer counts
        .filter(keyValuePair -> keyValuePair.contains("calls"))
        .map(keyValuePair -> keyValuePair.split("="))
        .collect(Collectors.toMap(
            keyValuePair -> keyValuePair[0],
            keyValuePair -> Integer.parseInt(keyValuePair[1])));
  }
}
