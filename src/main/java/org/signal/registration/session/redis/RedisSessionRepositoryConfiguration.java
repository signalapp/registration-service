/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import io.lettuce.core.RedisURI;
import io.micronaut.context.annotation.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("redis-session-repository")
class RedisSessionRepositoryConfiguration {

  private RedisURI uri;

  private Duration commandTimeout = Duration.ofSeconds(1);

  public RedisURI getUri() {
    return uri;
  }

  public void setUri(final RedisURI uri) {
    this.uri = uri;
  }

  public Duration getCommandTimeout() {
    return commandTimeout;
  }

  public void setCommandTimeout(final Duration commandTimeout) {
    this.commandTimeout = commandTimeout;
  }
}
