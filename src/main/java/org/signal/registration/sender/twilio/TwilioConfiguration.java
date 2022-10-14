/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import javax.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.List;

@Context
@ConfigurationProperties("twilio")
public class TwilioConfiguration {

  @NotBlank
  private String accountSid;

  @NotBlank
  private String apiKeySid;

  @NotBlank
  private String apiKeySecret;

  public String getAccountSid() {
    return accountSid;
  }

  public void setAccountSid(final String accountSid) {
    this.accountSid = accountSid;
  }

  public String getApiKeySid() {
    return apiKeySid;
  }

  public void setApiKeySid(final String apiKeySid) {
    this.apiKeySid = apiKeySid;
  }

  public String getApiKeySecret() {
    return apiKeySecret;
  }

  public void setApiKeySecret(final String apiKeySecret) {
    this.apiKeySecret = apiKeySecret;
  }
}
