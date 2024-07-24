/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.messagebird.classic;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import javax.annotation.Nullable;

/**
 * @param messageRepeatCount The number of times the TTS call should repeat the verification message
 * @param sessionTtl         How long verification sessions are valid for
 * @param supportedLanguages Languages that messagebird voice supports as translation targets
 */
@Context
@ConfigurationProperties("messagebird.voice")
public record MessageBirdVoiceConfiguration(
    @Bindable(defaultValue = "3") @NotNull int messageRepeatCount,
    @Bindable(defaultValue = "PT10M") @NotNull Duration sessionTtl,
    @Nullable List<@NotBlank String> supportedLanguages) {

  // See https://developers.messagebird.com/api/voice-messaging/#the-voice-message-object
  private static List<String> DEFAULT_SUPPORTED_LANGUAGES = List.of(
      "cy-gb",
      "da-dk",
      "de-de",
      "el-gr",
      "en-au",
      "en-gb",
      "en-gb-wls",
      "en-in",
      "en-us",
      "es-es",
      "es-mx",
      "es-us",
      "fr-ca",
      "fr-fr",
      "id-id",
      "is-is",
      "it-it",
      "ja-jp",
      "ko-kr",
      "ms-my",
      "nb-no",
      "nl-nl",
      "pl-pl",
      "pt-br",
      "pt-pt",
      "ro-ro",
      "ru-ru",
      "sv-se",
      "ta-in",
      "th-th",
      "tr-tr",
      "vi-vn",
      "zh-cn",
      "zh-hk");

  public MessageBirdVoiceConfiguration {
    if (supportedLanguages == null) {
      supportedLanguages = DEFAULT_SUPPORTED_LANGUAGES;
    }
  }

}
