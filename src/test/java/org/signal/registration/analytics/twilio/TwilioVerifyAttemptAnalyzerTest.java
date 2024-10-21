/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.verify.v2.VerificationAttempt;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.context.event.ApplicationEventPublisher;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.analytics.AbstractAttemptAnalyzer;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.analytics.Money;
import org.signal.registration.sender.twilio.verify.TwilioVerifySender;
import reactor.core.publisher.Flux;

class TwilioVerifyAttemptAnalyzerTest {

  private AttemptPendingAnalysisRepository repository;
  private ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher;

  private TwilioVerifyAttemptAnalyzer twilioVerifyAttemptAnalyzer;

  private static final Instant CURRENT_TIME = Instant.now().truncatedTo(ChronoUnit.SECONDS);

  @BeforeEach
  void setUp() {
    repository = mock(AttemptPendingAnalysisRepository.class);

    //noinspection unchecked
    attemptAnalyzedEventPublisher = mock(ApplicationEventPublisher.class);

    final TwilioVerifyPriceEstimator twilioVerifyPriceEstimator = mock(TwilioVerifyPriceEstimator.class);
    when(twilioVerifyPriceEstimator.estimatePrice(any(), any(), any())).thenReturn(Optional.empty());

    twilioVerifyAttemptAnalyzer = new TwilioVerifyAttemptAnalyzer(repository,
        twilioVerifyPriceEstimator,
        attemptAnalyzedEventPublisher,
        new SimpleMeterRegistry());
  }

  @Test
  void estimatePricingForAttempts() {
    final AttemptPendingAnalysis attempt = AttemptPendingAnalysis.newBuilder()
        .setRemoteId("attempt")
        .setTimestampEpochMillis(CURRENT_TIME.toEpochMilli())
        .build();

    twilioVerifyAttemptAnalyzer.estimatePricingForAttempts(Flux.just(attempt));

    verify(repository).remove(TwilioVerifySender.SENDER_NAME, attempt.getRemoteId());

    verify(attemptAnalyzedEventPublisher).publishEvent(argThat(event ->
        event.attemptPendingAnalysis().getRemoteId().equalsIgnoreCase(attempt.getRemoteId())));

    verifyNoMoreInteractions(attemptAnalyzedEventPublisher);
  }
}
