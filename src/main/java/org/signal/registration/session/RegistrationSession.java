/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.session;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.core.annotation.Nullable;
import org.signal.registration.sender.VerificationCodeSender;
import java.util.Arrays;
import java.util.Objects;

/**
 * A registration session stores information about a current attempt to register a phone number. Registration sessions
 * are created when a client first requests that a verification code be sent to a phone number and store all state
 * related to the registration attempt including the phone number being registered, the {@link VerificationCodeSender}
 * responsible for sending and verifying registration codes, and any persistent data that sender may need to verify
 * codes.
 *
 * @param phoneNumber the phone number to be verified and registered
 * @param sender the {@code VerificationCodeSender} instances responsible for sending and verifying codes sent to the
 *               given phone number
 * @param sessionData opaque data stored on behalf of the given {@code sender} to facilitate verification code checks
 * @param verifiedCode the known-verified code, if any, or {@code null} if this session has not yet concluded with a
 *                     successful verification
 *
 * @see SessionRepository
 */
public record RegistrationSession(Phonenumber.PhoneNumber phoneNumber,
                                  VerificationCodeSender sender,
                                  byte[] sessionData,
                                  @Nullable String verifiedCode) {

  @Override
  public boolean equals(final Object other) {
    if (this == other)
      return true;
    if (other == null || getClass() != other.getClass())
      return false;
    RegistrationSession session = (RegistrationSession) other;
    return phoneNumber.equals(session.phoneNumber) && sender.equals(session.sender) && Arrays.equals(sessionData,
        session.sessionData) && Objects.equals(verifiedCode, session.verifiedCode);
  }

  @Override
  public int hashCode() {
    int result = Objects.hash(phoneNumber, sender, verifiedCode);
    result = 31 * result + Arrays.hashCode(sessionData);
    return result;
  }
}
