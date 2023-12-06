/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.classic;

import com.twilio.type.PhoneNumber;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

@Context
@ConfigurationProperties("twilio.voice")
class TwilioVoiceConfiguration {

  @NotEmpty
  private List<PhoneNumber> phoneNumbers;

  @NotNull
  private URI cdnUri;

  private List<@NotBlank String> supportedLanguages = List.of("am",
      "ar",
      "bg",
      "bn",
      "bs",
      "cs",
      "da",
      "de",
      "el",
      "en-AU",
      "en-BD",
      "en-CN",
      "en-GB",
      "en-IN",
      "en-NZ",
      "en-PK",
      "en-SG",
      "en-US",
      "es",
      "es-ES",
      "es-MX",
      "et",
      "fa",
      "fi",
      "fr",
      "fr-CA",
      "he",
      "hi",
      "hr",
      "ht",
      "hu",
      "hy",
      "id",
      "is",
      "it",
      "ja",
      "kk",
      "km",
      "ko",
      "lo",
      "lt",
      "lv",
      "mr",
      "my",
      "nl",
      "nl-BE",
      "no",
      "pa",
      "pl",
      "pt",
      "pt-BR",
      "ro",
      "ru",
      "si",
      "sk",
      "sl",
      "so",
      "sq",
      "sr",
      "sv",
      "ta",
      "te",
      "th",
      "tr",
      "uk",
      "ur",
      "vi",
      "zh",
      "zh-HK",
      "zh-TW");

  private Duration sessionTtl = Duration.ofMinutes(10);

  public List<PhoneNumber> getPhoneNumbers() {
    return phoneNumbers;
  }

  public void setPhoneNumbers(final List<String> phoneNumbers) {
    this.phoneNumbers = phoneNumbers.stream().map(PhoneNumber::new).toList();
  }

  public URI getCdnUri() {
    return cdnUri;
  }

  public void setCdnUri(final URI cdnUri) {
    this.cdnUri = cdnUri;
  }

  public List<String> getSupportedLanguages() {
    return supportedLanguages;
  }

  public void setSupportedLanguages(final List<String> supportedLanguages) {
    this.supportedLanguages = supportedLanguages;
  }

  public Duration getSessionTtl() {
    return sessionTtl;
  }

  public void setSessionTtl(final Duration sessionTtl) {
    this.sessionTtl = sessionTtl;
  }
}
