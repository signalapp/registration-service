/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.infobip;

import com.infobip.ApiException;
import com.infobip.model.MessageStatus;
import io.micronaut.http.HttpStatus;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.signal.registration.sender.SenderFraudBlockException;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.util.CompletionExceptions;

public class InfobipExceptions {
  private static final Set<HttpStatus> REJECTED_HTTP_STATUS_CODES = Set.of(
      HttpStatus.BAD_REQUEST,
      HttpStatus.UNAUTHORIZED
  );

  // See https://www.infobip.com/docs/essentials/response-status-and-error-codes
  private static final Set<Integer> REJECTED_GROUP_IDS = Set.of(
      2, // Message not delivered
      4, // Expired
      5  // Rejected by Infobip
  );

  /**
   * Attempts to wrap an Infobip {@link com.infobip.ApiException} in a more specific exception type. If the given
   * exception does not have a classifiable error code, then the original exception is returned.
   *
   * @param exception the Infobip ApiException to wrap in a more specific exception type
   *
   * @return the potentially-wrapped throwable
   */
  public static Exception toSenderException(final ApiException exception) {
    Optional<HttpStatus> maybeHttpStatus;
    try {
      maybeHttpStatus = Optional.of(HttpStatus.valueOf(exception.responseStatusCode()));
    } catch (IllegalArgumentException ignored) {
      maybeHttpStatus = Optional.empty();
    }

    return maybeHttpStatus
        .filter(REJECTED_HTTP_STATUS_CODES::contains)
        .map(ignored -> (Exception) new SenderRejectedRequestException(exception))
        .orElse(exception);
  }

  public static @Nullable String getErrorCode(@NotNull final Throwable throwable) {
    Throwable unwrapped = CompletionExceptions.unwrap(throwable);

    while (!(unwrapped instanceof ApiException) && unwrapped.getCause() != null) {
      unwrapped = unwrapped.getCause();
    }

    if (unwrapped instanceof ApiException apiException) {
      return String.valueOf(apiException.responseStatusCode());
    }

    if (unwrapped instanceof InfobipRejectedRequestException infobipException) {
      return infobipException.getStatusCode();
    }

    return null;
  }

  public static void maybeThrowSenderFraudBlockException(final MessageStatus status) throws SenderFraudBlockException {
    // ID 87 is "SIGNALS_BLOCKED", which is defined as "Message has been rejected due to an anti-fraud mechanism"
    if (status.getId() == 87) {
      throw new SenderFraudBlockException("Message has been rejected due to an anti-fraud mechanism");
    }
  }

  public static void maybeThrowInfobipRejectedRequestException(final MessageStatus status) throws InfobipRejectedRequestException {
    if (REJECTED_GROUP_IDS.contains(status.getGroupId())) {
      throw new InfobipRejectedRequestException(status);
    }
  }
}
