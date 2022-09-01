/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.verify;

class TwilioVerifySmsSenderTest extends AbstractTwilioVerifySenderTest {

  @Override
  protected AbstractTwilioVerifySender getSender() {
    return new TwilioVerifySmsSender(TWILIO_VERIFY_CONFIGURATION);
  }
}
