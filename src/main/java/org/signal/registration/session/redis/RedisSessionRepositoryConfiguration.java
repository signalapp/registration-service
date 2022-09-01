/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session.redis;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("redis-session-repository")
class RedisSessionRepositoryConfiguration {

  private String uri;

  public String getUri() {
    return uri;
  }

  public void setUri(final String uri) {
    this.uri = uri;
  }
}
