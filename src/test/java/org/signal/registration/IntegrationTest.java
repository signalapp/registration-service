/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.signal.registration.rpc.CheckVerificationCodeRequest;
import org.signal.registration.rpc.CheckVerificationCodeResponse;
import org.signal.registration.rpc.CreateRegistrationSessionRequest;
import org.signal.registration.rpc.CreateRegistrationSessionResponse;
import org.signal.registration.rpc.MessageTransport;
import org.signal.registration.rpc.RegistrationServiceGrpc;
import org.signal.registration.rpc.SendVerificationCodeRequest;
import org.signal.registration.rpc.SendVerificationCodeResponse;
import org.signal.registration.sender.LastDigitsOfPhoneNumberSenderSelectionStrategy;
import org.signal.registration.sender.LastDigitsOfPhoneNumberVerificationCodeSender;
import org.signal.registration.sender.SenderSelectionStrategy;

@MicronautTest
public class IntegrationTest {

  @Inject
  private RegistrationServiceGrpc.RegistrationServiceBlockingStub blockingStub;

  @MockBean
  SenderSelectionStrategy senderSelectionStrategy() {
    return new LastDigitsOfPhoneNumberSenderSelectionStrategy(
        new LastDigitsOfPhoneNumberVerificationCodeSender());
  }

  @Test
  void register() {
    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setE164(12025550123L)
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(sendVerificationCodeResponse.getSessionId())
            .setVerificationCode("550123")
            .build());

    assertTrue(checkVerificationCodeResponse.getVerified());
  }

  @Test
  void registerWithExplicitSessionCreation() {
    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(12025550123L)
            .build());

    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(sendVerificationCodeResponse.getSessionId())
            .setVerificationCode("550123")
            .build());

    assertTrue(checkVerificationCodeResponse.getVerified());
  }

  @Test
  void registerIncorrectCode() {
    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setE164(12025551234L)
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(sendVerificationCodeResponse.getSessionId())
            .setVerificationCode("777777")
            .build());

    assertFalse(checkVerificationCodeResponse.getVerified());
  }

  @Test
  void registerNoSession() {
    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(ByteString.copyFrom(new byte[16]))
            .setVerificationCode("550123")
            .build());

    assertFalse(checkVerificationCodeResponse.getVerified());
  }
}
