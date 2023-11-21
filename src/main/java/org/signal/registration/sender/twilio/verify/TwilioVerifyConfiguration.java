/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.verify;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import javax.annotation.Nullable;
import javax.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.List;

@Context
@ConfigurationProperties("twilio.verify")
record TwilioVerifyConfiguration(@NotBlank String serviceSid,
                                 @Nullable String serviceFriendlyName,
                                 @NotBlank String androidAppHash,
                                 @Nullable String customTemplateSid,
                                 @Nullable List<@NotBlank String> customTemplateSupportedLanguages,
                                 @Nullable List<@NotBlank String> supportedLanguages) {

  TwilioVerifyConfiguration {
    if (supportedLanguages == null) {
      supportedLanguages = Collections.emptyList();
    }
    if (customTemplateSupportedLanguages == null) {
      customTemplateSupportedLanguages = Collections.emptyList();
    }
  }
}
