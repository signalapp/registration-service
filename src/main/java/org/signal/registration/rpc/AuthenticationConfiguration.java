/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import javax.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.Map;

@Context
@ConfigurationProperties("authentication")
public class AuthenticationConfiguration {

  private Map<String, @NotBlank String> apiKeys = Collections.emptyMap();

  public Map<String, String> getApiKeys() {
    return apiKeys;
  }

  public void setApiKeys(final Map<String, String> apiKeys) {
    this.apiKeys = apiKeys;
  }
}
