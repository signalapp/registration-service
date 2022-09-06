/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An API key interceptor intercepts all gRPC calls and requires that they include a recognized API key.
 */
@Singleton
class ApiKeyInterceptor implements ServerInterceptor, Ordered {

  private final Map<String, String> usernamesByApiKey;

  @VisibleForTesting
  static final Metadata.Key<String> API_KEY_METADATA_KEY =
      Metadata.Key.of("x-signal-api-key", Metadata.ASCII_STRING_MARSHALLER);

  static final Context.Key<String> CONTEXT_USERNAME_KEY = Context.key("username");

  private static final Logger logger = LoggerFactory.getLogger(ApiKeyInterceptor.class);

  public ApiKeyInterceptor(final AuthenticationConfiguration configuration) {
    usernamesByApiKey = configuration.getApiKeys().entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));

    if (usernamesByApiKey.isEmpty()) {
      logger.warn("No authentication credentials provided; callers will be unable to authenticate with this service");
    }
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> call,
      final Metadata headers,
      final ServerCallHandler<ReqT, RespT> next) {

    final String apiKey = headers.get(API_KEY_METADATA_KEY);

    if (StringUtils.isNotBlank(apiKey)) {
      final String username = usernamesByApiKey.get(apiKey);

      if (username != null) {
        final Context context = Context.current().withValue(CONTEXT_USERNAME_KEY, username);
        return Contexts.interceptCall(context, call, headers, next);
      } else {
        call.close(Status.UNAUTHENTICATED.withDescription("API key not recognized"), headers);
      }
    } else {
      call.close(Status.UNAUTHENTICATED.withDescription("No API key provided"), headers);
    }

    return new ServerCall.Listener<>() {};
  }

  @Override
  public int getOrder() {
    return RegistrationServiceGrpcEndpoint.ORDER_AUTHENTICATION;
  }
}
