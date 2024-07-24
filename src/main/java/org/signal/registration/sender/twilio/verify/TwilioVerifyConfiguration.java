/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.verify;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import jakarta.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

@Context
@ConfigurationProperties("twilio.verify")
record TwilioVerifyConfiguration(@NotBlank String serviceSid,
                                 @Nullable String serviceFriendlyName,
                                 @NotBlank String androidAppHash,
                                 @Nullable String customTemplateSid,
                                 @Nullable List<@NotBlank String> customTemplateSupportedLanguages,
                                 @Nullable List<@NotBlank String> supportedLanguages) {

  //For a current list of supported languages, please see https://www.twilio.com/docs/verify/supported-languages
  private static List<String> DEFAULT_SUPPORTED_LANGUAGES = List.of(
      "af",
      "ar",
      "ca",
      "zh",
      "zh-CN",
      "zh-HK",
      "hr",
      "cs",
      "da",
      "nl",
      "en",
      "en-GB",
      "et",
      "fi",
      "fr",
      "de",
      "el",
      "he",
      "hi",
      "hu",
      "id",
      "it",
      "ja",
      "kn",
      "ko",
      "lt",
      "ms",
      "mr",
      "nb",
      "pl",
      "pt-BR",
      "pt",
      "ro",
      "ru",
      "sk",
      "es",
      "sv",
      "tl",
      "te",
      "th",
      "tr",
      "uk",
      "vi");

  TwilioVerifyConfiguration {
    if (supportedLanguages == null) {
      supportedLanguages = DEFAULT_SUPPORTED_LANGUAGES;
    }
    if (customTemplateSupportedLanguages == null) {
      customTemplateSupportedLanguages = Collections.emptyList();
    }
  }
}
