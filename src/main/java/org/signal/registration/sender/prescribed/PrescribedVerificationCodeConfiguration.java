/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.prescribed;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import javax.validation.constraints.NotBlank;
import java.util.Map;

@Context
@ConfigurationProperties("prescribed-verification-codes")
public class PrescribedVerificationCodeConfiguration {

  private Map<Phonenumber.PhoneNumber, String> verificationCodes;

  public Map<Phonenumber.PhoneNumber, @NotBlank String> getVerificationCodes() {
    return verificationCodes;
  }

  public void setVerificationCodes(final Map<Phonenumber.PhoneNumber, String> verificationCodes) {
    this.verificationCodes = verificationCodes;
  }
}
