/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.twilio;

import com.twilio.exception.ApiException;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import org.signal.registration.util.CompletionExceptions;

public class TwilioErrorCodeExtractor {
  private TwilioErrorCodeExtractor() {}

  public static @Nullable String extract(@NotNull Throwable throwable) {
    if (CompletionExceptions.unwrap(throwable) instanceof ApiException apiException) {
      return String.valueOf(apiException.getCode());
    }
    return null;
  }
}
