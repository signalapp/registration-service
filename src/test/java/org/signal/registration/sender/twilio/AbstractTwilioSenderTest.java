/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.twilio.exception.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class AbstractTwilioSenderTest {

  @Test
  void findCausalApiException() {
    final ApiException apiException = new ApiException("test");

    assertEquals(Optional.of(apiException), AbstractTwilioSender.findCausalApiException(apiException));
    assertEquals(Optional.of(apiException), AbstractTwilioSender.findCausalApiException(new CompletionException(apiException)));
    assertEquals(Optional.empty(), AbstractTwilioSender.findCausalApiException(new UncheckedIOException(new IOException())));
    assertEquals(Optional.empty(), AbstractTwilioSender.findCausalApiException(null));
  }
}
