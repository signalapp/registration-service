/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import io.grpc.CallCredentials;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.RegistrationService;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.util.UUIDUtil;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@MicronautTest
@Property(name = "authentication.api-keys.test", value = "${random.uuid}")
class ApiKeyInterceptorTest {

  @MockBean(RegistrationService.class)
  RegistrationService registrationService() {
    return mock(RegistrationService.class);
  }

  @Inject
  private RegistrationServiceGrpc.RegistrationServiceBlockingStub blockingStub;

  @Inject
  private RegistrationService registrationService;

  @Value("${authentication.api-keys.test}")
  private String apiKey;

  private static final CheckVerificationCodeRequest CHECK_VERIFICATION_CODE_REQUEST =
      CheckVerificationCodeRequest.newBuilder()
          .setSessionId(UUIDUtil.uuidToByteString(UUID.randomUUID()))
          .setVerificationCode("test")
          .build();

  private static class ApiKeyCallCredentials extends CallCredentials {

    private final String apiKey;

    private ApiKeyCallCredentials(final String apiKey) {
      this.apiKey = apiKey;
    }

    @Override
    public void applyRequestMetadata(final RequestInfo requestInfo,
        final Executor appExecutor,
        final MetadataApplier applier) {

      final Metadata metadata = new Metadata();
      metadata.put(ApiKeyInterceptor.API_KEY_METADATA_KEY, apiKey);

      applier.apply(metadata);
    }

    @Override
    public void thisUsesUnstableApi() {
    }
  }

  @BeforeEach
  void setUp() {
    when(registrationService.checkRegistrationCode(any(), any()))
        .thenReturn(CompletableFuture.completedFuture(RegistrationSession.newBuilder().build()));
  }

  @Test
  void interceptCallNoKey() {
    @SuppressWarnings("ResultOfMethodCallIgnored") final StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class, () -> blockingStub.checkVerificationCode(
            CHECK_VERIFICATION_CODE_REQUEST));

    assertEquals(Status.UNAUTHENTICATED.getCode(), exception.getStatus().getCode());

    verifyNoInteractions(registrationService);
  }

  @Test
  void interceptCallIncorrectKey() {
    @SuppressWarnings("ResultOfMethodCallIgnored") final StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class,
            () -> blockingStub.withCallCredentials(new ApiKeyCallCredentials("incorrect-api-key"))
                .checkVerificationCode(CHECK_VERIFICATION_CODE_REQUEST));

    assertEquals(Status.UNAUTHENTICATED.getCode(), exception.getStatus().getCode());

    verifyNoInteractions(registrationService);
  }

  @Test
  void interceptCallCorrectKey() {
    assertDoesNotThrow(() -> blockingStub.withCallCredentials(new ApiKeyCallCredentials(apiKey))
        .checkVerificationCode(CHECK_VERIFICATION_CODE_REQUEST));

    verify(registrationService).checkRegistrationCode(any(), any());
  }
}
