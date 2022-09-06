/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.time.Duration;

@Factory
class RedisConnectionFactory {

  @Singleton
  @Bean(preDestroy = "shutdown")
  RedisClient redisClient(final RedisSessionRepositoryConfiguration configuration, final MeterRegistry meterRegistry) {
    final MicrometerOptions options = MicrometerOptions.builder().histogram(true).build();

    final ClientResources clientResources = DefaultClientResources.builder()
        .commandLatencyRecorder(new MicrometerCommandLatencyRecorder(meterRegistry, options))
        .build();

    final RedisClient redisClient = RedisClient.create(clientResources, configuration.getUri());

    redisClient.setOptions(ClientOptions.builder()
        .timeoutOptions(TimeoutOptions.builder()
            .timeoutCommands(true)
            .fixedTimeout(configuration.getCommandTimeout())
            .build())
        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
        .build());

    return redisClient;
  }

  @Singleton
  @Bean(preDestroy = "close")
  StatefulRedisConnection<byte[], byte[]> redisConnection(final RedisClient redisClient) {
    return redisClient.connect(ByteArrayCodec.INSTANCE);
  }
}
