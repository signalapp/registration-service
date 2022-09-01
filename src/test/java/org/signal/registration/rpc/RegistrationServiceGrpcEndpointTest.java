/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.RegistrationService;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
  void sendVerificationCode() throws NumberParseException {
    final long e164 = 12025550123L;
    final UUID sessionUuid = UUID.randomUUID();

    when(registrationService.sendRegistrationCode(any(), any(), any(), any()))
        .thenReturn(CompletableFuture.completedFuture(sessionUuid));

    final SendVerificationCodeResponse response =
        blockingStub.sendVerificationCode(SendVerificationCodeRequest.newBuilder()
            .setE164(e164)
            .setTransport(org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS)
            .setAcceptLanguage("en")
            .build());

    final Phonenumber.PhoneNumber expectedPhoneNumber = PhoneNumberUtil.getInstance().parse("+" + e164, null);

    verify(registrationService)
        .sendRegistrationCode(MessageTransport.SMS, expectedPhoneNumber, Locale.LanguageRange.parse("en"), ClientType.UNKNOWN);

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
