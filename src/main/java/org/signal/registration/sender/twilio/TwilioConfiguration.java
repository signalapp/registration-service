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

  private List<@NotBlank String> alwaysUseVerifyRegions = Collections.emptyList();

  private List<@NotBlank String> neverUseVerifyRegions = Collections.emptyList();

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

  public List<String> getAlwaysUseVerifyRegions() {
    return alwaysUseVerifyRegions;
  }

  public void setAlwaysUseVerifyRegions(final List<String> alwaysUseVerifyRegions) {
    this.alwaysUseVerifyRegions = alwaysUseVerifyRegions;
  }

  public List<String> getNeverUseVerifyRegions() {
    return neverUseVerifyRegions;
  }

  public void setNeverUseVerifyRegions(final List<String> neverUseVerifyRegions) {
    this.neverUseVerifyRegions = neverUseVerifyRegions;
  }
}
