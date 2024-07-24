/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.twilio;

import com.twilio.exception.ApiException;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.sender.SenderFraudBlockException;
import org.signal.registration.sender.SenderInvalidParametersException;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.sender.SenderRejectedTransportException;
import org.signal.registration.util.CompletionExceptions;

public class ApiExceptions {

  private static final int INVALID_PARAM_ERROR_CODE = 60200;

  private static final Set<Integer> SUSPECTED_FRAUD_ERROR_CODES = Set.of(
      30450, // Message delivery blocked (SMS Pumping Protection)
      60410, // Verification delivery attempt blocked (Fraud Guard)
      60605  // Verification delivery attempt blocked (geo permissions)
  );

  private static final Set<Integer> REJECTED_REQUEST_ERROR_CODES = Set.of(
      20404, // Not found
      21211, // Invalid 'to' phone number
      21215, // Geo Permission configuration is not permitting call
      21216, // Call blocked by Twilio blocklist
      21610, // Attempt to send to unsubscribed recipient
      60200, // Invalid parameter
      60202, // Max check attempts reached
      60203, // Max send attempts reached
      60212  // Too many concurrent requests for phone number
  );

  private static final Set<Integer> REJECTED_TRANSPORT_ERROR_CODES = Set.of(
      21408, // Permission to send an SMS has not been enabled for the region indicated by the 'To' number
      21612, // The 'To' phone number is not currently reachable via SMS
      21614, // 'To' number is not a valid mobile number
      60205  // SMS is not supported by landline phone number
  );

  // Codes that can be retried directly
  private static final Set<Integer> INTERNAL_RETRY_ERROR_CODES = Set.of(
      20429 // Too Many Requests
  );

  private static final Duration EXTERNAL_RETRY_INTERVAL = Duration.ofMinutes(1);

  private ApiExceptions() {}

  public static @Nullable String extractErrorCode(@NotNull final Throwable throwable) {
    Throwable unwrapped = CompletionExceptions.unwrap(throwable);

    while (unwrapped instanceof SenderRejectedRequestException e && unwrapped.getCause() != null) {
      unwrapped = e.getCause();
    }

    if (unwrapped instanceof ApiException apiException) {
      return String.valueOf(apiException.getCode());
    }

    return null;
  }

  public static boolean isRetriable(@NotNull final Throwable throwable) {
    final Throwable unwrapped = CompletionExceptions.unwrap(throwable);
    if (unwrapped instanceof ApiException apiException) {
      return INTERNAL_RETRY_ERROR_CODES.contains(apiException.getCode());
    }
    return false;
  }

  private static Optional<SenderInvalidParametersException.ParamName> extractInvalidParameter(
      @NotNull final ApiException apiException) {
    // attempt to parse out specific information about why the request was rejected
    // https://www.twilio.com/docs/api/errors/60200
    final String message = apiException.getMessage();
    if (message == null) {
      return Optional.empty();
    }

    // Invalid parameter `To`: +XXXXXXXXXXX
    if (message.toLowerCase().contains("to")) {
      return Optional.of(SenderInvalidParametersException.ParamName.NUMBER);
    }

    // Invalid parameter: Locale
    if (message.toLowerCase().contains("locale")) {
      return Optional.of(SenderInvalidParametersException.ParamName.LOCALE);
    }

    return Optional.empty();
  }

  /**
   * Attempts to wrap a Twilio {@link ApiException} in a more specific exception type. If the given throwable is not
   * an {@code ApiException} or does not have a classifiable error code, then the original throwable is returned.
   *
   * @param throwable the throwable to wrap in a more specific exception type
   *
   * @return the potentially-wrapped throwable
   */
  public static Throwable toSenderException(final Throwable throwable) {
    if (CompletionExceptions.unwrap(throwable) instanceof ApiException apiException) {
      if (INVALID_PARAM_ERROR_CODE == apiException.getCode()) {
        return new SenderInvalidParametersException(throwable, extractInvalidParameter(apiException));
      } else if (SUSPECTED_FRAUD_ERROR_CODES.contains(apiException.getCode())) {
        return new SenderFraudBlockException(throwable);
      } else if (REJECTED_REQUEST_ERROR_CODES.contains(apiException.getCode())) {
        return new SenderRejectedRequestException(throwable);
      } else if (REJECTED_TRANSPORT_ERROR_CODES.contains(apiException.getCode())) {
        return new SenderRejectedTransportException(throwable);
      } else if (INTERNAL_RETRY_ERROR_CODES.contains(apiException.getCode())) {
        return new RateLimitExceededException(EXTERNAL_RETRY_INTERVAL);
      }
    }

    return throwable;
  }
}
