/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.twilio.exception.ApiException;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.signal.registration.sender.SenderRejectedRequestException;

class TwilioExceptionsTest {

  @Test
  void extractErrorCode() {
    assertEquals("1234", TwilioExceptions.extractErrorCode(new ApiException("Test", 1234, null, 4321, null, null, null, null)));

    assertEquals("1234", TwilioExceptions.extractErrorCode(new CompletionException(
        new ApiException("Test", 1234, null, 4321, null, null, null, null))));

    assertEquals("1234", TwilioExceptions.extractErrorCode(new CompletionException(
        new SenderRejectedRequestException(
            new ApiException("Test", 1234, null, 4321, null, null, null, null)))));
  }

  @Test
  void handleNullErrorCodes() {
    final ApiException nullErrorCodeException = new ApiException("Test", null, null, 4321, null, null, null, null);

    assertEquals(Optional.empty(), TwilioExceptions.toSenderRejectedException(nullErrorCodeException));
    assertEquals("http_4321", TwilioExceptions.extractErrorCode(nullErrorCodeException));
  }
}
