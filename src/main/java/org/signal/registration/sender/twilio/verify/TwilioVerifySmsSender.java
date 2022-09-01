/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.verify;

import jakarta.inject.Singleton;
import org.signal.registration.sender.MessageTransport;

/**
 * A concrete implementation of an {@code AbstractTwilioVerifySender} that sends verification codes via the Twilio
 * Verify SMS channel.
 */
@Singleton
public class TwilioVerifySmsSender extends AbstractTwilioVerifySender {

  public TwilioVerifySmsSender(final TwilioVerifyConfiguration configuration) {
    super(configuration);
  }

  @Override
  public MessageTransport getTransport() {
    return MessageTransport.SMS;
  }
}
