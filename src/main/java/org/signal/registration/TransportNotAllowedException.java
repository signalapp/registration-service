/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import org.signal.registration.session.RegistrationSession;

/**
 * Indicates that a request to send a verification code could not be fulfilled because the destination phone number does
 * not support the requested transport (e.g. the caller made a request to send an SMS to a landline number).
 */
public class TransportNotAllowedException extends Exception {

  private final RegistrationSession registrationSession;

  public TransportNotAllowedException(final Throwable cause, final RegistrationSession registrationSession) {
    super(cause);

    this.registrationSession = registrationSession;
  }

  public RegistrationSession getRegistrationSession() {
    return registrationSession;
  }
}
