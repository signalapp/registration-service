/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import org.signal.registration.Environments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * If configured, an allow-all API key interceptor replaces the {@link ApiKeyInterceptor} and allows all RPC requests
 * regardless of whether the caller has provided an API key or whether it is valid. This authentication strategy should
 * never be used in a production setting or any setting "armed" with an ability to send real SMS or voice messages.
 */
@Singleton
@Replaces(ApiKeyInterceptor.class)
@Requires(env = {Environments.DEVELOPMENT, Environment.TEST})
@Requires(missingProperty = "authentication.api-keys")
class AllowAllApiKeyInterceptor implements ServerInterceptor, Ordered {

  private static final Logger logger = LoggerFactory.getLogger(AllowAllApiKeyInterceptor.class);

  public AllowAllApiKeyInterceptor() {
    logger.warn("gRPC authentication is disabled; do not use this configuration in a production setting");
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> call,
      final Metadata headers,
      final ServerCallHandler<ReqT, RespT> next) {

    return Contexts.interceptCall(Context.current(), call, headers, next);
  }

  @Override
  public int getOrder() {
    return 1;
  }
}
