/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.RegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Singleton
public class RegistrationServiceGrpcEndpoint extends RegistrationServiceGrpc.RegistrationServiceImplBase {

  final RegistrationService registrationService;

  static int ORDER_METRICS = 1;
  static int ORDER_AUTHENTICATION = 2;

  private static final Logger logger = LoggerFactory.getLogger(RegistrationServiceGrpcEndpoint.class);

  public RegistrationServiceGrpcEndpoint(final RegistrationService registrationService) {
    this.registrationService = registrationService;
  }

  @Override
  public void sendVerificationCode(final SendVerificationCodeRequest request,
      final StreamObserver<SendVerificationCodeResponse> responseObserver) {

    try {
      final Phonenumber.PhoneNumber phoneNumber = request.getE164() > 0 ?
          PhoneNumberUtil.getInstance().parse("+" + request.getE164(), null) :
          null;

      final UUID existingSessionId = !request.getSessionId().isEmpty() ?
          uuidFromByteString(request.getSessionId()) : null;

      registrationService.sendRegistrationCode(getServiceMessageTransport(request.getTransport()),
              phoneNumber,
              existingSessionId,
              getLanguageRanges(request.getAcceptLanguage()),
              getServiceClientType(request.getClientType()))
          .whenComplete((sessionId, throwable) -> {
            if (throwable == null) {
              responseObserver.onNext(SendVerificationCodeResponse.newBuilder()
                  .setSessionId(uuidToByteString(sessionId))
                  .build());

              responseObserver.onCompleted();
            } else {
              logger.warn("Failed to send registration code", throwable);
              responseObserver.onError(new StatusException(Status.INTERNAL));
            }
          });
    } catch (final NumberParseException | IllegalArgumentException e) {
      responseObserver.onError(new StatusException(Status.INVALID_ARGUMENT));
    }
  }

  @Override
  public void checkVerificationCode(final CheckVerificationCodeRequest request,
      final StreamObserver<CheckVerificationCodeResponse> responseObserver) {

    registrationService.checkRegistrationCode(uuidFromByteString(request.getSessionId()), request.getVerificationCode())
        .whenComplete((verified, throwable) -> {
          if (throwable == null) {
            responseObserver.onNext(CheckVerificationCodeResponse.newBuilder()
                .setVerified(verified)
                .build());

            responseObserver.onCompleted();
          } else {
            logger.warn("Failed to check verification code", throwable);
            responseObserver.onError(new StatusException(Status.INTERNAL));
          }
        });
  }

  @VisibleForTesting
  static List<Locale.LanguageRange> getLanguageRanges(final String acceptLanguageList) {
    if (StringUtils.isBlank(acceptLanguageList)) {
      return Collections.emptyList();
    }

    try {
      return Locale.LanguageRange.parse(acceptLanguageList);
    } catch (final IllegalArgumentException e) {
      logger.debug("Could not get acceptable languages from language list; \"{}\"", acceptLanguageList, e);
      return Collections.emptyList();
    }
  }

  @VisibleForTesting
  static ByteString uuidToByteString(final UUID uuid) {
    final ByteBuffer buffer = ByteBuffer.allocate(16);
    buffer.putLong(uuid.getMostSignificantBits());
    buffer.putLong(uuid.getLeastSignificantBits());
    buffer.flip();

    return ByteString.copyFrom(buffer);
  }

  @VisibleForTesting
  static UUID uuidFromByteString(final ByteString byteString) {
    if (byteString.size() != 16) {
      throw new IllegalArgumentException("UUID byte string must be 16 bytes, but was actually " + byteString.size());
    }

    final ByteBuffer buffer = byteString.asReadOnlyByteBuffer();
    return new UUID(buffer.getLong(), buffer.getLong());
  }

  @VisibleForTesting
  static MessageTransport getServiceMessageTransport(final org.signal.registration.rpc.MessageTransport rpcTransport) {
    return switch (rpcTransport) {
      case MESSAGE_TRANSPORT_SMS -> MessageTransport.SMS;
      case MESSAGE_TRANSPORT_VOICE -> MessageTransport.VOICE;
      default -> throw new IllegalArgumentException("Unrecognized RPC transport: " + rpcTransport);
    };
  }

  @VisibleForTesting
  static ClientType getServiceClientType(final org.signal.registration.rpc.ClientType rpcClientType) {
    return switch (rpcClientType) {
      case CLIENT_TYPE_IOS -> ClientType.IOS;
      case CLIENT_TYPE_ANDROID_WITH_FCM -> ClientType.ANDROID_WITH_FCM;
      case CLIENT_TYPE_ANDROID_WITHOUT_FCM -> ClientType.ANDROID_WITHOUT_FCM;
      case CLIENT_TYPE_UNSPECIFIED, UNRECOGNIZED -> ClientType.UNKNOWN;
    };
  }
}
