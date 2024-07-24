/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import javax.annotation.Nullable;

@ConfigurationProperties("verification.voice")
public record VerificationVoiceConfiguration(@Nullable List<@NotBlank String> supportedLanguages) {

  public VerificationVoiceConfiguration {
    if (supportedLanguages == null) {
      supportedLanguages = DEFAULT_SUPPORTED_LANGUAGES;
    }
  }

  private static List<String> DEFAULT_SUPPORTED_LANGUAGES = List.of(
      "af",
      "ar",
      "az",
      "bg",
      "bn",
      "bs",
      "ca",
      "cs",
      "da",
      "de",
      "el",
      "en",
      "es",
      "et",
      "eu",
      "fa",
      "fi",
      "fr",
      "ga",
      "gl",
      "gu",
      "he",
      "hi",
      "hr",
      "hu",
      "id",
      "it",
      "ja",
      "ka",
      "kk",
      "km",
      "kn",
      "ko",
      "ky",
      "lt",
      "lv",
      "mk",
      "ml",
      "mr",
      "ms",
      "my",
      "nb",
      "nl",
      "pa",
      "pl",
      "pt",
      "pt-BR",
      "pt-PT",
      "ro",
      "ru",
      "sk",
      "sl",
      "sq",
      "sr",
      "sv",
      "sw",
      "ta",
      "te",
      "th",
      "tl",
      "tr",
      "uk",
      "ur",
      "vi",
      "zh",
      "zh-CN",
      "zh-HK",
      "zh-TW");
}
