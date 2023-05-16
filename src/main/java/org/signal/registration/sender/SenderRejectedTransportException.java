/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender;

/**
 * Indicates that a request to a verification code sender was well-formed, but the sender declined to process the
 * request specifically because the caller attempted to send a verification code to a phone number that does not support
 * the specified transport (i.e. the caller made a request to send an SMS message to a landline phone number).
 */
public class SenderRejectedTransportException extends SenderRejectedRequestException {

  public SenderRejectedTransportException(final Throwable cause) {
    super(cause);
  }
}
