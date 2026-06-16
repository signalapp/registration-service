/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import io.grpc.Status;
import jakarta.inject.Singleton;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.AttemptExpiredException;
import org.signal.registration.NoVerificationCodeSentException;
import org.signal.registration.RegistrationService;
import org.signal.registration.SessionAlreadyVerifiedException;
import org.signal.registration.TransportNotAllowedException;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.NoSenderAvailableException;
import org.signal.registration.sender.SenderFraudBlockException;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionMetadata;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.util.MessageTransports;
import org.signal.registration.util.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class RegistrationServiceGrpcEndpoint extends SimpleRegistrationServiceGrpc.RegistrationServiceImplBase {

  final RegistrationService registrationService;

  private static final Logger logger = LoggerFactory.getLogger(RegistrationServiceGrpcEndpoint.class);

  public RegistrationServiceGrpcEndpoint(final RegistrationService registrationService) {
    this.registrationService = registrationService;
  }

  @Override
  protected Throwable mapException(final Throwable throwable) {
    return switch (throwable) {
      case IllegalArgumentException ignored -> Status.INVALID_ARGUMENT.asException();
      case UncheckedIOException ignored -> Status.INTERNAL.asException();
      default -> super.mapException(throwable);
    };
  }

  @Override
  public CreateRegistrationSessionResponse createSession(final CreateRegistrationSessionRequest request) {
    try {
      final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().parse("+" + request.getE164(), null);

      final RegistrationSession session =
          registrationService.createRegistrationSession(phoneNumber, request.getRateLimitCollationKey(),
              SessionMetadata.newBuilder()
                  .setAccountExistsWithE164(request.getAccountExistsWithE164())
                  .setMcc(request.getMcc())
                  .setMnc(request.getMnc())
                  .build());

      return CreateRegistrationSessionResponse.newBuilder()
          .setSessionMetadata(registrationService.buildSessionMetadata(session))
          .build();
    } catch (final RateLimitExceededException e) {
      final CreateRegistrationSessionError.Builder errorBuilder = CreateRegistrationSessionError.newBuilder()
          .setErrorType(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_RATE_LIMITED)
          .setMayRetry(e.getRetryAfterDuration().isPresent());

      e.getRetryAfterDuration()
          .ifPresent(retryAfterDuration -> errorBuilder.setRetryAfterSeconds(retryAfterDuration.getSeconds()));

      return CreateRegistrationSessionResponse.newBuilder()
          .setError(errorBuilder.build())
          .build();
    } catch (final NumberParseException e) {
      return CreateRegistrationSessionResponse.newBuilder()
          .setError(CreateRegistrationSessionError.newBuilder()
              .setErrorType(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_ILLEGAL_PHONE_NUMBER)
              .setMayRetry(false)
              .build())
          .build();
    } catch (final RuntimeException e) {
      logger.warn("Failed to create session", e);
      throw e;
    }
  }

  @Override
  public GetRegistrationSessionMetadataResponse getSessionMetadata(final GetRegistrationSessionMetadataRequest request) {
    try {
      final RegistrationSession session =
          registrationService.getRegistrationSession(UUIDUtil.uuidFromByteString(request.getSessionId()));

      return GetRegistrationSessionMetadataResponse.newBuilder()
          .setSessionMetadata(registrationService.buildSessionMetadata(session))
          .build();
    } catch (final SessionNotFoundException e) {
      return GetRegistrationSessionMetadataResponse.newBuilder()
          .setError(GetRegistrationSessionMetadataError.newBuilder()
              .setErrorType(GetRegistrationSessionMetadataErrorType.GET_REGISTRATION_SESSION_METADATA_ERROR_TYPE_NOT_FOUND)
              .build())
          .build();
    } catch (final RuntimeException e) {
      if (!(e instanceof IllegalArgumentException)) {
        logger.warn("Failed to get session metadata", e);
      }

      throw e;
    }
  }

  @Override
  public SendVerificationCodeResponse sendVerificationCode(final SendVerificationCodeRequest request) {

    try {
      final RegistrationSession session = registrationService.sendVerificationCode(
          MessageTransports.getSenderMessageTransportFromRpcTransport(request.getTransport()),
          UUIDUtil.uuidFromByteString(request.getSessionId()),
          StringUtils.stripToNull(request.getSenderName()),
          getLanguageRanges(request.getAcceptLanguage()),
          getServiceClientType(request.getClientType()));

      return SendVerificationCodeResponse.newBuilder()
          .setSessionMetadata(registrationService.buildSessionMetadata(session))
          .build();
    } catch (final SessionAlreadyVerifiedException e) {
      return SendVerificationCodeResponse.newBuilder()
          .setSessionMetadata(registrationService.buildSessionMetadata(e.getRegistrationSession()))
          .setError(SendVerificationCodeError.newBuilder()
              .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SESSION_ALREADY_VERIFIED)
              .setMayRetry(false)
              .build())
          .build();
    } catch (final SessionNotFoundException e) {
      return SendVerificationCodeResponse.newBuilder()
          .setError(SendVerificationCodeError.newBuilder()
              .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SESSION_NOT_FOUND)
              .setMayRetry(false)
              .build())
          .build();
    } catch (final RateLimitExceededException e) {
      final RegistrationSessionMetadata sessionMetadata =
          registrationService.buildSessionMetadata(e.getRegistrationSession()
              .orElseThrow(() -> new IllegalStateException("Rate limit exception did not include a session reference")));

      final SendVerificationCodeError.Builder errorBuilder = SendVerificationCodeError.newBuilder()
          .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED)
          .setMayRetry(e.getRetryAfterDuration().isPresent());

      e.getRetryAfterDuration()
          .ifPresent(
              retryAfterDuration -> errorBuilder.setRetryAfterSeconds(retryAfterDuration.getSeconds()));

      return SendVerificationCodeResponse.newBuilder()
          .setError(errorBuilder.build())
          .setSessionMetadata(sessionMetadata)
          .build();
    } catch (final SenderFraudBlockException e) {
      return SendVerificationCodeResponse.newBuilder()
          .setError(SendVerificationCodeError.newBuilder()
              .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SUSPECTED_FRAUD)
              .setMayRetry(false)
              .build())
          .build();
    } catch (final SenderRejectedRequestException e) {
      return SendVerificationCodeResponse.newBuilder()
          .setError(SendVerificationCodeError.newBuilder()
              .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SENDER_REJECTED)
              .setMayRetry(false)
              .build())
          .build();
    } catch (final TransportNotAllowedException e) {
      return SendVerificationCodeResponse.newBuilder()
          .setSessionMetadata(registrationService.buildSessionMetadata(e.getRegistrationSession()))
          .setError(SendVerificationCodeError.newBuilder()
              .setErrorType(
                  SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_TRANSPORT_NOT_ALLOWED)
              .setMayRetry(false)
              .build())
          .build();
    } catch (final NoSenderAvailableException e) {
      return SendVerificationCodeResponse.newBuilder()
          .setError(SendVerificationCodeError.newBuilder()
              .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_NO_SENDER_AVAILABLE)
              .setMayRetry(false)
              .build()
          ).build();
    } catch (final RuntimeException e) {
      if (!(e instanceof IllegalArgumentException)) {
        logger.warn("Failed to send verification code", e);
      }

      throw e;
    }
  }

  @Override
  public CheckVerificationCodeResponse checkVerificationCode(final CheckVerificationCodeRequest request) {

    try {
      final UUID sessionId = UUIDUtil.uuidFromByteString(request.getSessionId());
      final RegistrationSession session =
          registrationService.checkVerificationCode(sessionId, request.getVerificationCode());

      return CheckVerificationCodeResponse.newBuilder()
          .setSessionMetadata(registrationService.buildSessionMetadata(session))
          .build();
    } catch (final NoVerificationCodeSentException e) {
      return CheckVerificationCodeResponse.newBuilder()
          .setSessionMetadata(registrationService.buildSessionMetadata(e.getRegistrationSession()))
          .setError(CheckVerificationCodeError.newBuilder()
              .setErrorType(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_NO_CODE_SENT)
              .setMayRetry(false)
              .build())
          .build();
    } catch (final SessionNotFoundException e) {
      return CheckVerificationCodeResponse.newBuilder()
          .setError(CheckVerificationCodeError.newBuilder()
              .setErrorType(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_SESSION_NOT_FOUND)
              .setMayRetry(false)
              .build())
          .build();
    } catch (final RateLimitExceededException e) {
      final RegistrationSessionMetadata sessionMetadata =
          registrationService.buildSessionMetadata(e.getRegistrationSession()
              .orElseThrow(() -> new IllegalStateException("Rate limit exception did not include a session reference")));

      final CheckVerificationCodeError.Builder errorBuilder = CheckVerificationCodeError.newBuilder()
          .setErrorType(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED)
          .setMayRetry(e.getRetryAfterDuration().isPresent());

      e.getRetryAfterDuration()
          .ifPresent(retryAfterDuration -> errorBuilder.setRetryAfterSeconds(retryAfterDuration.getSeconds()));

      return CheckVerificationCodeResponse.newBuilder()
          .setError(errorBuilder.build())
          .setSessionMetadata(sessionMetadata)
          .build();
    } catch (final AttemptExpiredException e) {
      return CheckVerificationCodeResponse.newBuilder()
          .setError(CheckVerificationCodeError.newBuilder()
              .setErrorType(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_ATTEMPT_EXPIRED)
              .setMayRetry(false)
              .build())
          .build();
    } catch (final RuntimeException e) {
      if (!(e instanceof IllegalArgumentException)) {
        logger.warn("Failed to check verification code", e);
      }

      throw e;
    }
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
  static ClientType getServiceClientType(final org.signal.registration.rpc.ClientType rpcClientType) {
    return switch (rpcClientType) {
      case CLIENT_TYPE_IOS -> ClientType.IOS;
      case CLIENT_TYPE_ANDROID_WITH_FCM -> ClientType.ANDROID_WITH_FCM;
      case CLIENT_TYPE_ANDROID_WITHOUT_FCM -> ClientType.ANDROID_WITHOUT_FCM;
      case CLIENT_TYPE_UNSPECIFIED, UNRECOGNIZED -> ClientType.UNKNOWN;
    };
  }
}
