/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.rpc;

import com.google.common.annotations.VisibleForTesting;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.metrics.MetricsUtil;

@Singleton
public class CallMetricsInterceptor implements ServerInterceptor, Ordered {

  @VisibleForTesting
  static final String RPC_CALL_TIMER_NAME = MetricsUtil.name(CallMetricsInterceptor.class, "rpcCallDuration");

  @VisibleForTesting
  static final String RPC_CALL_COUNTER_NAME = MetricsUtil.name(CallMetricsInterceptor.class, "rpcCalls");

  @VisibleForTesting
  static final String METHOD_TAG_NAME = "method";

  @VisibleForTesting
  static final String STATUS_TAG_NAME = "status";

  @VisibleForTesting
  static final String USER_TAG_NAME = "user";

  private static final Context.Key<Timer.Sample> CONTEXT_TIMER_SAMPLE_KEY = Context.key("timerSample");

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(final ServerCall<ReqT, RespT> call,
      final Metadata headers,
      final ServerCallHandler<ReqT, RespT> next) {

    final Context context = Context.current().withValue(CONTEXT_TIMER_SAMPLE_KEY, Timer.start());

    return Contexts.interceptCall(context, new ForwardingServerCall.SimpleForwardingServerCall<>(call) {

      @Override
      public void close(final Status status, final Metadata trailers) {
        super.close(status, trailers);

        Tags tags = Tags.of(
            METHOD_TAG_NAME, getMethodDescriptor().getBareMethodName(),
            STATUS_TAG_NAME, status.getCode().name());

        final String username = ApiKeyInterceptor.CONTEXT_USERNAME_KEY.get();

        if (StringUtils.isNotBlank(username)) {
          tags = tags.and(USER_TAG_NAME, username);
        }

        Metrics.counter(RPC_CALL_COUNTER_NAME, tags).increment();
        CONTEXT_TIMER_SAMPLE_KEY.get().stop(
            Metrics.timer(RPC_CALL_TIMER_NAME, METHOD_TAG_NAME, getMethodDescriptor().getBareMethodName()));
      }
    }, headers, next);
  }

  @Override
  public int getOrder() {
    return RegistrationServiceGrpcEndpoint.ORDER_METRICS;
  }
}
