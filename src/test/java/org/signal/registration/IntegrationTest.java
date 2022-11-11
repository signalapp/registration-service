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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.rpc.CheckVerificationCodeRequest;
import org.signal.registration.rpc.CheckVerificationCodeResponse;
import org.signal.registration.rpc.CreateRegistrationSessionErrorType;
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

  @MockBean
  SenderSelectionStrategy senderSelectionStrategy() {
    return new LastDigitsOfPhoneNumberSenderSelectionStrategy(new LastDigitsOfPhoneNumberVerificationCodeSender());
  }

  @MockBean(named = "session-creation")
  @Named("session-creation")
  RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter() {
    @SuppressWarnings("unchecked") final RateLimiter<Phonenumber.PhoneNumber> rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.checkRateLimit(any())).thenReturn(CompletableFuture.completedFuture(null));

    return rateLimiter;
  }

  @Inject
  private RegistrationServiceGrpc.RegistrationServiceBlockingStub blockingStub;

  @Inject
  private RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter;

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

    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(sendVerificationCodeResponse.getSessionId())
            .setVerificationCode(LastDigitsOfPhoneNumberVerificationCodeSender.getVerificationCode(phoneNumber))
            .build());

    assertTrue(checkVerificationCodeResponse.getVerified());
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
            .setSessionId(sendVerificationCodeResponse.getSessionId())
            .setVerificationCode("incorrect")
            .build());

    assertFalse(checkVerificationCodeResponse.getVerified());
  }

  @Test
  void createSessionRateLimited() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");
    final Duration retryAfter = Duration.ofSeconds(60);

    when(sessionCreationRateLimiter.checkRateLimit(phoneNumber))
        .thenReturn(CompletableFuture.failedFuture(new RateLimitExceededException(retryAfter)));

    final CreateRegistrationSessionResponse createRegistrationSessionResponse =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(phoneNumberToLong(phoneNumber))
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.ERROR,
        createRegistrationSessionResponse.getResponseCase());

    assertEquals(CreateRegistrationSessionErrorType.ERROR_TYPE_RATE_LIMITED,
        createRegistrationSessionResponse.getError().getErrorType());

    assertEquals(retryAfter.getSeconds(), createRegistrationSessionResponse.getError().getRetryAfterSeconds());
  }

  @Test
  void sendVerificationCodeNoSession() {
    @SuppressWarnings("ResultOfMethodCallIgnored") final StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class,
            () -> blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
                .setTransport(MessageTransport.MESSAGE_TRANSPORT_SMS)
                .build()));

    assertEquals(Status.INVALID_ARGUMENT, exception.getStatus());
  }

  @Test
  void checkVerificationCodeNoSession() {
    final CheckVerificationCodeResponse checkVerificationCodeResponse =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(ByteString.copyFrom(new byte[16]))
            .setVerificationCode("550123")
            .build());

    assertFalse(checkVerificationCodeResponse.getVerified());
  }

  private static long phoneNumberToLong(final Phonenumber.PhoneNumber phoneNumber) {
    final String e164 = PhoneNumberUtil.getInstance().format(phoneNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
    return Long.parseLong(e164, 1, e164.length(), 10);
  }
}
