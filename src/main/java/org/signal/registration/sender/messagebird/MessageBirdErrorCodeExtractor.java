/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.messagebird;

import com.messagebird.exceptions.GeneralException;
import com.messagebird.exceptions.MessageBirdException;
import com.messagebird.exceptions.NotFoundException;
import com.messagebird.exceptions.UnauthorizedException;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;
import org.signal.registration.util.CompletionExceptions;

public class MessageBirdErrorCodeExtractor {

  public static @Nullable String extract(@NotNull Throwable throwable) {
    throwable = CompletionExceptions.unwrap(throwable);
    if (throwable instanceof MessageBirdException mbException && !mbException.getErrors().isEmpty()) {
      return String.valueOf(mbException.getErrors().get(0).getCode());
    }
    if (throwable instanceof GeneralException generalException) {
      return String.valueOf(generalException.getResponseCode());
    }
    if (throwable instanceof NotFoundException) {
      return "notFound";
    }
    if (throwable instanceof UnauthorizedException) {
      return "unauthorized";
    }
    return null;
  }
}
