/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.classic;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import javax.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Context
@ConfigurationProperties("twilio.messaging")
class TwilioMessagingConfiguration {

  @NotBlank
  private String nanpaMessagingServiceSid;

  @NotBlank
  private String globalMessagingServiceSid;

  @NotBlank
  private String androidAppHash;

  private List<@NotBlank String> supportedLanguages;

  private Map<String, String> verificationMessageVariants = Collections.emptyMap();

  private Duration sessionTtl = Duration.ofMinutes(10);

  public String getNanpaMessagingServiceSid() {
    return nanpaMessagingServiceSid;
  }

  public void setNanpaMessagingServiceSid(final String nanpaMessagingServiceSid) {
    this.nanpaMessagingServiceSid = nanpaMessagingServiceSid;
  }

  public String getGlobalMessagingServiceSid() {
    return globalMessagingServiceSid;
  }

  public void setGlobalMessagingServiceSid(final String globalMessagingServiceSid) {
    this.globalMessagingServiceSid = globalMessagingServiceSid;
  }

  public String getAndroidAppHash() {
    return androidAppHash;
  }

  public void setAndroidAppHash(final String androidAppHash) {
    this.androidAppHash = androidAppHash;
  }

  public List<String> getSupportedLanguages() {
    return supportedLanguages;
  }

  public void setSupportedLanguages(final List<String> supportedLanguages) {
    this.supportedLanguages = supportedLanguages;
  }

  public Map<String, String> getVerificationMessageVariants() {
    return verificationMessageVariants;
  }

  public void setVerificationMessageVariants(final Map<String, String> verificationMessageVariants) {
    // Coerce region codes to uppercase
    this.verificationMessageVariants = verificationMessageVariants.entrySet()
        .stream()
        .collect(Collectors.toUnmodifiableMap(entry -> entry.getKey().toUpperCase(), Map.Entry::getValue));
  }

  public Duration getSessionTtl() {
    return sessionTtl;
  }

  public void setSessionTtl(final Duration sessionTtl) {
    this.sessionTtl = sessionTtl;
  }
}
