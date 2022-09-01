/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micronaut.context.annotation.Factory;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;

@Factory
class RedisConnectionFactory {

  private final RedisClient redisClient;

  RedisConnectionFactory(final RedisSessionRepositoryConfiguration configuration) {
    this.redisClient = RedisClient.create(configuration.getUri());
  }

  @Singleton
  StatefulRedisConnection<String, String> redisConnection() {
    return redisClient.connect();
  }

  @PreDestroy
  void preDestroy() {
    redisClient.close();
  }
}
