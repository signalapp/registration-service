/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import javax.validation.constraints.NotBlank;

@Context
@ConfigurationProperties("twilio")
public class TwilioConfiguration {

  @NotBlank
  private String accountSid;

  @NotBlank
  private String authToken;

  public String getAccountSid() {
    return accountSid;
  }

  public void setAccountSid(final String accountSid) {
    this.accountSid = accountSid;
  }

  public String getAuthToken() {
    return authToken;
  }

  public void setAuthToken(final String authToken) {
    this.authToken = authToken;
  }
}
