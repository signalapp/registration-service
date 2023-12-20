/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import io.grpc.Status;
import io.grpc.StatusException;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.AttemptExpiredException;
import org.signal.registration.NoVerificationCodeSentException;
import org.signal.registration.RegistrationService;
import org.signal.registration.SessionAlreadyVerifiedException;
import org.signal.registration.TransportNotAllowedException;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderFraudBlockException;
import org.signal.registration.sender.SenderRejectedRequestException;
import org.signal.registration.session.SessionMetadata;
import org.signal.registration.session.SessionNotFoundException;
import org.signal.registration.util.MessageTransports;
import org.signal.registration.util.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

@Singleton
public class RegistrationServiceGrpcEndpoint extends ReactorRegistrationServiceGrpc.RegistrationServiceImplBase {

  final RegistrationService registrationService;

  private static final Logger logger = LoggerFactory.getLogger(RegistrationServiceGrpcEndpoint.class);

  public RegistrationServiceGrpcEndpoint(final RegistrationService registrationService) {
    this.registrationService = registrationService;
  }

  @Override
  public Mono<CreateRegistrationSessionResponse> createSession(final CreateRegistrationSessionRequest request) {
    return Mono.fromCallable(() -> PhoneNumberUtil.getInstance().parse("+" + request.getE164(), null))
        .flatMap(phoneNumber -> Mono.fromFuture(
            registrationService.createRegistrationSession(phoneNumber, SessionMetadata.newBuilder()
                .setAccountExistsWithE164(request.getAccountExistsWithE164())
                .build())))
        .map(session -> CreateRegistrationSessionResponse.newBuilder()
            .setSessionMetadata(registrationService.buildSessionMetadata(session))
            .build())
        .onErrorResume(RateLimitExceededException.class, rateLimitExceededException -> {
          final CreateRegistrationSessionError.Builder errorBuilder = CreateRegistrationSessionError.newBuilder()
              .setErrorType(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_RATE_LIMITED)
              .setMayRetry(rateLimitExceededException.getRetryAfterDuration().isPresent());

          rateLimitExceededException.getRetryAfterDuration()
              .ifPresent(retryAfterDuration -> errorBuilder.setRetryAfterSeconds(retryAfterDuration.getSeconds()));

          return Mono.just(CreateRegistrationSessionResponse.newBuilder()
              .setError(errorBuilder.build())
              .build());
        })
        .onErrorReturn(NumberParseException.class, CreateRegistrationSessionResponse.newBuilder()
                .setError(CreateRegistrationSessionError.newBuilder()
                    .setErrorType(CreateRegistrationSessionErrorType.CREATE_REGISTRATION_SESSION_ERROR_TYPE_ILLEGAL_PHONE_NUMBER)
                    .setMayRetry(false)
                    .build())
                .build())
        .doOnError(throwable -> logger.warn("Failed to create session", throwable));
  }

  @Override
  public Mono<GetRegistrationSessionMetadataResponse> getSessionMetadata(final GetRegistrationSessionMetadataRequest request) {
    return Mono.fromSupplier(() -> UUIDUtil.uuidFromByteString(request.getSessionId()))
        .flatMap(sessionId -> Mono.fromFuture(registrationService.getRegistrationSession(sessionId)))
        .map(session -> GetRegistrationSessionMetadataResponse.newBuilder()
            .setSessionMetadata(registrationService.buildSessionMetadata(session))
            .build())
        .onErrorReturn(SessionNotFoundException.class, GetRegistrationSessionMetadataResponse.newBuilder()
            .setError(GetRegistrationSessionMetadataError.newBuilder()
                .setErrorType(GetRegistrationSessionMetadataErrorType.GET_REGISTRATION_SESSION_METADATA_ERROR_TYPE_NOT_FOUND)
                .build())
            .build())
        .doOnError(throwable -> !(throwable instanceof IllegalArgumentException),
            throwable -> logger.warn("Failed to get session metadata", throwable))
        .onErrorMap(IllegalArgumentException.class, ignored -> new StatusException(Status.INVALID_ARGUMENT));
  }

  @Override
  public Mono<SendVerificationCodeResponse> sendVerificationCode(final SendVerificationCodeRequest request) {
    return Mono.fromSupplier(() -> Tuples.of(
            MessageTransports.getSenderMessageTransportFromRpcTransport(request.getTransport()),
        UUIDUtil.uuidFromByteString(request.getSessionId()),
            getLanguageRanges(request.getAcceptLanguage()),
            getServiceClientType(request.getClientType())
        ))
        .flatMap(tuple -> {
          final MessageTransport messageTransport = tuple.getT1();
          final UUID sessionId = tuple.getT2();
          final List<Locale.LanguageRange> languageRanges = tuple.getT3();
          final ClientType clientType = tuple.getT4();
          @Nullable final String senderName = StringUtils.stripToNull(request.getSenderName());

          return Mono.fromFuture(
              registrationService.sendVerificationCode(messageTransport, sessionId, senderName, languageRanges, clientType));
        })
        .map(session -> SendVerificationCodeResponse.newBuilder()
            .setSessionMetadata(registrationService.buildSessionMetadata(session))
            .build())
        .onErrorResume(SessionAlreadyVerifiedException.class, sessionAlreadyVerifiedException -> Mono.just(
            SendVerificationCodeResponse.newBuilder()
                .setSessionMetadata(registrationService.buildSessionMetadata(sessionAlreadyVerifiedException.getRegistrationSession()))
                .setError(SendVerificationCodeError.newBuilder()
                    .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SESSION_ALREADY_VERIFIED)
                    .setMayRetry(false)
                    .build())
                .build()))
        .onErrorReturn(SessionNotFoundException.class, SendVerificationCodeResponse.newBuilder()
                .setError(SendVerificationCodeError.newBuilder()
                    .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SESSION_NOT_FOUND)
                    .setMayRetry(false)
                    .build())
                .build())
        .onErrorResume(RateLimitExceededException.class, rateLimitExceededException -> Mono.fromSupplier(() -> {
          final SendVerificationCodeError.Builder errorBuilder = SendVerificationCodeError.newBuilder()
              .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED)
              .setMayRetry(rateLimitExceededException.getRetryAfterDuration().isPresent());

          rateLimitExceededException.getRetryAfterDuration()
              .ifPresent(retryAfterDuration -> errorBuilder.setRetryAfterSeconds(retryAfterDuration.getSeconds()));

          return SendVerificationCodeResponse.newBuilder()
              .setError(errorBuilder.build())
              .setSessionMetadata(registrationService.buildSessionMetadata(
                  rateLimitExceededException.getRegistrationSession().orElseThrow(() ->
                      new IllegalStateException("Rate limit exception did not include a session reference"))))
              .build();
        }))
        .onErrorReturn(SenderFraudBlockException.class, SendVerificationCodeResponse.newBuilder()
            .setError(SendVerificationCodeError.newBuilder()
                .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SUSPECTED_FRAUD)
                .setMayRetry(false)
                .build())
            .build())
        .onErrorReturn(SenderRejectedRequestException.class, SendVerificationCodeResponse.newBuilder()
                .setError(SendVerificationCodeError.newBuilder()
                    .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_SENDER_REJECTED)
                    .setMayRetry(false)
                    .build())
                .build())
        .onErrorResume(TransportNotAllowedException.class, transportNotAllowedException -> Mono.fromSupplier(() ->
            SendVerificationCodeResponse.newBuilder()
                .setSessionMetadata(registrationService.buildSessionMetadata(transportNotAllowedException.getRegistrationSession()))
                .setError(SendVerificationCodeError.newBuilder()
                    .setErrorType(SendVerificationCodeErrorType.SEND_VERIFICATION_CODE_ERROR_TYPE_TRANSPORT_NOT_ALLOWED)
                    .setMayRetry(false)
                    .build())
                .build()))
        .doOnError(throwable -> !(throwable instanceof IllegalArgumentException),
            throwable -> logger.warn("Failed to send verification code", throwable))
        .onErrorMap(IllegalArgumentException.class, ignored -> new StatusException(Status.INVALID_ARGUMENT));
  }

  @Override
  public Mono<CheckVerificationCodeResponse> checkVerificationCode(final CheckVerificationCodeRequest request) {
    return Mono.fromSupplier(() -> UUIDUtil.uuidFromByteString(request.getSessionId()))
        .flatMap(sessionId -> Mono.fromFuture(registrationService.checkVerificationCode(UUIDUtil.uuidFromByteString(request.getSessionId()), request.getVerificationCode())))
        .map(session -> CheckVerificationCodeResponse.newBuilder()
            .setSessionMetadata(registrationService.buildSessionMetadata(session))
            .build())
        .onErrorResume(NoVerificationCodeSentException.class, noVerificationCodeSentException -> Mono.just(CheckVerificationCodeResponse.newBuilder()
            .setSessionMetadata(registrationService.buildSessionMetadata(noVerificationCodeSentException.getRegistrationSession()))
            .setError(CheckVerificationCodeError.newBuilder()
                .setErrorType(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_NO_CODE_SENT)
                .setMayRetry(false)
                .build())
            .build()))
        .onErrorReturn(SessionNotFoundException.class, CheckVerificationCodeResponse.newBuilder()
            .setError(CheckVerificationCodeError.newBuilder()
                .setErrorType(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_SESSION_NOT_FOUND)
                .setMayRetry(false)
                .build())
            .build())
        .onErrorResume(RateLimitExceededException.class, rateLimitExceededException -> Mono.fromSupplier(() -> {
          final CheckVerificationCodeError.Builder errorBuilder = CheckVerificationCodeError.newBuilder()
              .setErrorType(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_RATE_LIMITED)
              .setMayRetry(rateLimitExceededException.getRetryAfterDuration().isPresent());

          rateLimitExceededException.getRetryAfterDuration()
              .ifPresent(retryAfterDuration -> errorBuilder.setRetryAfterSeconds(retryAfterDuration.getSeconds()));

          return CheckVerificationCodeResponse.newBuilder()
              .setError(errorBuilder.build())
              .setSessionMetadata(registrationService.buildSessionMetadata(
                  rateLimitExceededException.getRegistrationSession().orElseThrow(() ->
                      new IllegalStateException("Rate limit exception did not include a session reference"))))
              .build();
        }))
        .onErrorReturn(AttemptExpiredException.class, CheckVerificationCodeResponse.newBuilder()
            .setError(CheckVerificationCodeError.newBuilder()
                .setErrorType(CheckVerificationCodeErrorType.CHECK_VERIFICATION_CODE_ERROR_TYPE_ATTEMPT_EXPIRED)
                .setMayRetry(false)
                .build())
            .build())
        .doOnError(throwable -> !(throwable instanceof IllegalArgumentException),
            throwable -> logger.warn("Failed to check verification code", throwable))
        .onErrorMap(IllegalArgumentException.class, ignored -> new StatusException(Status.INVALID_ARGUMENT));
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
