/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.RegistrationService;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;

@MicronautTest
class RegistrationServiceGrpcEndpointTest {

  @MockBean(RegistrationService.class)
  RegistrationService registrationService() {
    return mock(RegistrationService.class);
  }

  @Inject
  private RegistrationServiceGrpc.RegistrationServiceBlockingStub blockingStub;

  @Inject
  private RegistrationService registrationService;

  @Test
  void createSession() {
    final UUID sessionId = UUID.randomUUID();

    when(registrationService.createRegistrationSession(any()))
        .thenReturn(CompletableFuture.completedFuture(sessionId));

    final CreateRegistrationSessionResponse response =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(18005550123L)
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.SESSION_METADATA, response.getResponseCase());
    assertEquals(RegistrationServiceGrpcEndpoint.uuidToByteString(sessionId), response.getSessionMetadata().getSessionId());
  }

  @Test
  void createSessionRateLimited() {
    final Duration retryAfter = Duration.ofSeconds(60);

    when(registrationService.createRegistrationSession(any()))
        .thenReturn(CompletableFuture.failedFuture(new RateLimitExceededException(retryAfter)));

    final CreateRegistrationSessionResponse response =
        blockingStub.createSession(CreateRegistrationSessionRequest.newBuilder()
            .setE164(18005550123L)
            .build());

    assertEquals(CreateRegistrationSessionResponse.ResponseCase.ERROR, response.getResponseCase());
    assertEquals(CreateRegistrationSessionErrorType.ERROR_TYPE_RATE_LIMITED, response.getError().getErrorType());
    assertEquals(retryAfter.toSeconds(), response.getError().getRetryAfterSeconds());
  }

  @Test
  void sendVerificationCode() {
    final UUID sessionUuid = UUID.randomUUID();

    when(registrationService.sendRegistrationCode(any(), any(), isNull(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(sessionUuid));

    final SendVerificationCodeResponse response =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setSessionId(RegistrationServiceGrpcEndpoint.uuidToByteString(sessionUuid))
            .setTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setAcceptLanguage("en")
            .build());

    verify(registrationService)
        .sendRegistrationCode(MessageTransport.SMS, sessionUuid, null, Locale.LanguageRange.parse("en"), ClientType.UNKNOWN);

    assertEquals(sessionUuid, RegistrationServiceGrpcEndpoint.uuidFromByteString(response.getSessionId()));
  }

  @Test
  void checkVerificationCode() {
    final UUID sessionId = UUID.randomUUID();
    final String verificationCode = "123456";

    when(registrationService.checkRegistrationCode(sessionId, verificationCode))
        .thenReturn(CompletableFuture.completedFuture(true));

    final CheckVerificationCodeResponse response =
        blockingStub.checkVerificationCode(CheckVerificationCodeRequest.newBuilder()
            .setSessionId(RegistrationServiceGrpcEndpoint.uuidToByteString(sessionId))
            .setVerificationCode(verificationCode)
            .build());

    verify(registrationService).checkRegistrationCode(sessionId, verificationCode);
    assertTrue(response.getVerified());
  }

  @Test
  void uuidToFromByteString() {
    final UUID uuid = UUID.randomUUID();

    assertEquals(uuid, RegistrationServiceGrpcEndpoint.uuidFromByteString(
        RegistrationServiceGrpcEndpoint.uuidToByteString(uuid)));
  }

  @Test
  void getServiceMessageTransport() {
    assertEquals(MessageTransport.SMS, RegistrationServiceGrpcEndpoint.getServiceMessageTransport(
        org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS));

    assertEquals(MessageTransport.VOICE, RegistrationServiceGrpcEndpoint.getServiceMessageTransport(
        org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_VOICE));

    //noinspection ResultOfMethodCallIgnored
    assertThrows(IllegalArgumentException.class, () -> RegistrationServiceGrpcEndpoint.getServiceMessageTransport(
        org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_UNSPECIFIED));
  }

  @ParameterizedTest
  @MethodSource
  void getServiceClientType(final org.signal.registration.rpc.ClientType rpcClientType, final ClientType expectedServiceClientType) {
    assertEquals(expectedServiceClientType, RegistrationServiceGrpcEndpoint.getServiceClientType(rpcClientType));
  }

  private static Stream<Arguments> getServiceClientType() {
    return Stream.of(
        Arguments.of(org.signal.registration.rpc.ClientType.CLIENT_TYPE_IOS, ClientType.IOS),
        Arguments.of(org.signal.registration.rpc.ClientType.CLIENT_TYPE_ANDROID_WITH_FCM, ClientType.ANDROID_WITH_FCM),
        Arguments.of(org.signal.registration.rpc.ClientType.CLIENT_TYPE_ANDROID_WITHOUT_FCM, ClientType.ANDROID_WITHOUT_FCM),
        Arguments.of(org.signal.registration.rpc.ClientType.CLIENT_TYPE_UNSPECIFIED, ClientType.UNKNOWN),
        Arguments.of(org.signal.registration.rpc.ClientType.UNRECOGNIZED, ClientType.UNKNOWN));
  }
}
