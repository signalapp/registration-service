/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.cli;

import com.google.protobuf.ByteString;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.signal.registration.rpc.CheckVerificationCodeRequest;
import org.signal.registration.rpc.CheckVerificationCodeResponse;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;

@CommandLine.Command(name = "check-verification-code",
    aliases = "check",
    description = "Check a verification code for a registration session")
public class CheckVerificationCode implements Runnable {

  @CommandLine.ParentCommand
  private RegistrationClient registrationClient;

  @CommandLine.Parameters(index = "0", description = "Hex-formatted registration session ID")
  private String sessionId;

  @CommandLine.Parameters(index = "1", description = "Verification code")
  private String verificationCode;

  @Override
  public void run() {
    try (final CloseableRegistrationServiceGrpcBlockingStubSupplier stubSupplier = registrationClient.getBlockingStubSupplier()) {
      final CheckVerificationCodeResponse response =
          stubSupplier.get().checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
              .setSessionId(ByteString.copyFrom(Hex.decodeHex(sessionId)))
              .setVerificationCode(verificationCode)
              .build());

      if (response.getVerified()) {
        System.out.println("Verified");
      } else {
        System.out.println("Not verified");
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } catch (final DecoderException e) {
      throw new IllegalArgumentException("Could not decode session ID as a hexadecimal value", e);
    }
  }
}
