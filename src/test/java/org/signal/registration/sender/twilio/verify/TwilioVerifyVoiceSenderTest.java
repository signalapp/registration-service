/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.verify;

class TwilioVerifyVoiceSenderTest extends AbstractTwilioVerifySenderTest {

  @Override
  protected AbstractTwilioVerifySender getSender() {
    return new TwilioVerifyVoiceSender(TWILIO_VERIFY_CONFIGURATION);
  }
}
