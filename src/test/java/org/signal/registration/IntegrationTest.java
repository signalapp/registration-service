/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.rpc.CheckVerificationCodeErrorType;
import org.signal.registration.rpc.CheckVerificationCodeRequest;
import org.signal.registration.rpc.CheckVerificationCodeResponse;
import org.signal.registration.rpc.CreateRegistrationSessionRequest;
import org.signal.registration.rpc.CreateRegistrationSessionResponse;
import org.signal.registration.rpc.GetRegistrationSessionMetadataErrorType;
import org.signal.registration.rpc.GetRegistrationSessionMetadataRequest;
import org.signal.registration.rpc.GetRegistrationSessionMetadataResponse;
import org.signal.registration.rpc.MessageTransport;
import org.signal.registration.rpc.RegistrationServiceGrpc;
import org.signal.registration.rpc.SendVerificationCodeErrorType;
import org.signal.registration.rpc.SendVerificationCodeRequest;
import org.signal.registration.rpc.SendVerificationCodeResponse;
import org.signal.registration.sender.AttemptData;
import org.signal.registration.sender.LastDigitsOfPhoneNumberVerificationCodeSender;
import org.signal.registration.sender.SenderRejectedTransportException;
import org.signal.registration.sender.SenderSelectionStrategy;
import org.signal.registration.sender.VerificationCodeSender;
import org.signal.registration.util.UUIDUtil;

@MicronautTest
public class IntegrationTest {

  @MockBean
  SenderSelectionStrategy senderSelectionStrategy = mock(SenderSelectionStrategy.class);

  @SuppressWarnings("unchecked")
  @MockBean(named = "session-creation")
  @Named("session-creation")
  RateLimiter<Pair<Phonenumber.PhoneNumber, String>> sessionCreationRateLimiter = mock(RateLimiter.class);

  @SuppressWarnings("unchecked")
  @MockBean(named = "send-sms-verification-code-per-number")
  @Named("send-sms-verification-code-per-number")
  RateLimiter<Phonenumber.PhoneNumber> sendSmsVerificationCodePerNumberRateLimiter = mock(RateLimiter.class);

  @SuppressWarnings("unchecked")
  @MockBean(named = "send-voice-verification-code-per-number")
  @Named("send-voice-verification-code-per-number")
  RateLimiter<Phonenumber.PhoneNumber> sendVoiceVerificationCodePerNumberRateLimiter = mock(RateLimiter.class);

  @SuppressWarnings("unchecked")
  @MockBean(named = "check-verification-code-per-number")
  @Named("check-verification-code-per-number")
  RateLimiter<Phonenumber.PhoneNumber> checkVerificationCodePerNumberRateLimiter = mock(RateLimiter.class);

  @Inject
  private RegistrationServiceGrpc.RegistrationServiceBlockingStub blockingStub;

  @BeforeEach
  void setUp() {
    when(sessionCreationRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));
    when(sendSmsVerificationCodePerNumberRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));
    when(sendVoiceVerificationCodePerNumberRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));
    when(checkVerificationCodePerNumberRateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    when(senderSelectionStrategy.chooseVerificationCodeSender(any(), any(), any(), any(), any(), any()))
        .thenReturn(new SenderSelectionStrategy.SenderSelection(
            new LastDigitsOfPhoneNumberVerificationCodeSender(),
            SenderSelectionStrategy.SelectionReason.CONFIGURED));
  }

  @Test
  void register() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    assertFalse(sendVerificationCodeResponse.hasError());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setVerificationCode(LastDigitsOfPhoneNumberVerificationCodeSender.getVerificationCode(phoneNumber))
            .build());

    assertTrue(checkVerificationCodeResponse.getSessionMetadata().getVerified());
  }

  @Test
  void registerIncorrectCode() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setVerificationCode("incorrect")
            .build());

    assertFalse(checkVerificationCodeResponse.getSessionMetadata().getVerified());
  }

  @Test
  void registerTransportNotAllowed() {
    final VerificationCodeSender smsNotSupportedSender = mock(VerificationCodeSender.class);

    when(smsNotSupportedSender.sendVerificationCode(eq(org.signal.registration.sender.MessageTransport.SMS), any(), any(), any()))
        .thenReturn(CompletableFuture.failedFuture(new SenderRejectedTransportException(new RuntimeException())));

    when(smsNotSupportedSender.sendVerificationCode(eq(org.signal.registration.sender.MessageTransport.VOICE), any(), any(), any()))
        .thenAnswer(invocation -> {
          final Phonenumber.PhoneNumber phoneNumber = invocation.getArgument(1);
          final byte[] senderData =
              LastDigitsOfPhoneNumberVerificationCodeSender.getVerificationCode(phoneNumber).getBytes(StandardCharsets.UTF_8);

          return CompletableFuture.completedFuture(new AttemptData(Optional.empty(), senderData));
        });

    when(smsNotSupportedSender.getName()).thenReturn("sms-not-supported");

    when(senderSelectionStrategy.chooseVerificationCodeSender(any(), any(), any(), any(), any(), any()))
        .thenReturn(new SenderSelectionStrategy.SenderSelection(
            smsNotSupportedSender,
            SenderSelectionStrategy.SelectionReason.CONFIGURED));

    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final SendVerificationCodeResponse sendVerificationCodeSmsResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_TRANSPORT_NOT_ALLOWED,
        sendVerificationCodeSmsResponse.getError().getErrorType());

    final SendVerificationCodeResponse sendVerificationCodeVoiceResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_VOICE)
            .build());

    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_UNSPECIFIED,
        sendVerificationCodeVoiceResponse.getError().getErrorType());
  }

  @Test
  void getSessionMetadata() throws NumberParseException {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final GetRegistrationSessionMetadataResponse getSessionMetadataResponse =
        blockingStub.getSessionMetadata(GetRegistrationSessionMetadataRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .build());

    assertEquals(GetRegistrationSessionMetadataResponse.ResponseCase.SESSION_METADATA,
        getSessionMetadataResponse.getResponseCase());

    assertEquals(createRegistrationSessionResponse.getSessionMetadata(),
        getSessionMetadataResponse.getSessionMetadata());

    assertEquals(phoneNumber,
        PhoneNumberUtil.getInstance().parse("+" + getSessionMetadataResponse.getSessionMetadata().getE164(), null));
  }

  @Test
  void getSessionMetadataNotFound() {
    final GetRegistrationSessionMetadataResponse getSessionMetadataResponse =
        blockingStub.getSessionMetadata(GetRegistrationSessionMetadataRequest.newBuilder()
            .setSessionId(UUIDUtil.uuidToByteString(UUID.randomUUID()))
            .build());

    assertEquals(GetRegistrationSessionMetadataResponse.ResponseCase.ERROR,
        getSessionMetadataResponse.getResponseCase());

    assertEquals(GetRegistrationSessionMetadataErrorType.GET_REGISTRATION_SESSION_METADATA_ERROR_TYPE_NOT_FOUND,
        getSessionMetadataResponse.getError().getErrorType());
  }

  @Test
  void sendVerificationCodeNoSession() {
    final StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class,
            () -> blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
                .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .build()));

    assertEquals(Status.INVALID_ARGUMENT, exception.getStatus());
  }

  @Test
  void sendVerificationCodeAlreadyVerified() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final SendVerificationCodeResponse sendVerificationCodeResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setVerificationCode(LastDigitsOfPhoneNumberVerificationCodeSender.getVerificationCode(phoneNumber))
            .build());

    assertTrue(checkVerificationCodeResponse.getSessionMetadata().getVerified());

    final SendVerificationCodeResponse sendVerificationCodeAfterVerificationResponse =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
            .build());

    assertTrue(sendVerificationCodeAfterVerificationResponse.hasError());
    assertFalse(sendVerificationCodeAfterVerificationResponse.getError().getMayRetry());
    assertTrue(sendVerificationCodeAfterVerificationResponse.hasSessionMetadata());

    assertEquals(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SESSION_ALREADY_VERIFIED,
        sendVerificationCodeAfterVerificationResponse.getError().getErrorType());
  }

  @Test
  void checkVerificationCodeNoSession() {
    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(ByteString.copyFrom(new byte[16]))
            .setVerificationCode("550123")
            .build());

    assertFalse(checkVerificationCodeResponse.getSessionMetadata().getVerified());
  }

  @Test
  void checkVerificationCodeNoCodeSent() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA,
        createRegistrationSessionResponse.getResponseCase());

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(createRegistrationSessionResponse.getSessionMetadata().getSessionId())
            .setVerificationCode(LastDigitsOfPhoneNumberVerificationCodeSender.getVerificationCode(phoneNumber))
            .build());

    assertTrue(checkVerificationCodeResponse.hasError());
    assertFalse(checkVerificationCodeResponse.getError().getMayRetry());
    assertTrue(checkVerificationCodeResponse.hasSessionMetadata());

    assertEquals(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_NO_CODE_SENT,
        checkVerificationCodeResponse.getError().getErrorType());
  }

  private static long phoneNumberToLong(final Phonenumber.PhoneNumber phoneNumber) {
    final String e164 = PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
    return Long.parseLong(e164, 1, e164.length(), 10);
  }
}
