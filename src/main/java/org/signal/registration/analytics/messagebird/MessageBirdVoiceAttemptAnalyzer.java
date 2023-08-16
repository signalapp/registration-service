/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.messagebird;

import com.messagebird.MessageBirdClient;
import com.messagebird.exceptions.MessageBirdException;
import com.messagebird.exceptions.NotFoundException;
import com.messagebird.objects.MessageResponse;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.signal.registration.analytics.AttemptAnalyzedEvent;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.util.CompletionExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Analyzes verification attempts from {@link MessageBirdVoiceSender}.
 */
@Singleton
class MessageBirdVoiceAttemptAnalyzer extends AbstractMessageBirdAttemptAnalyzer {

  private final MessageBirdClient messageBirdClient;
  private final Executor executor;

  private static final Logger logger = LoggerFactory.getLogger(MessageBirdVoiceAttemptAnalyzer.class);

  protected MessageBirdVoiceAttemptAnalyzer(final AttemptPendingAnalysisRepository repository,
      final ApplicationEventPublisher<AttemptAnalyzedEvent> attemptAnalyzedEventPublisher,
      final Clock clock,
      final MessageBirdPriceEstimator messageBirdPriceEstimator,
      final MessageBirdClient messageBirdClient,
      @Named(TaskExecutors.IO) final Executor executor) {

    super(repository, attemptAnalyzedEventPublisher, clock, messageBirdPriceEstimator);

    this.messageBirdClient = messageBirdClient;
    this.executor = executor;
  }

  @Override
  @Scheduled(fixedDelay = "${analytics.messagebird.voice.analysis-interval:4h}")
  protected void analyzeAttempts() {
    super.analyzeAttempts();
  }

  @Override
  protected String getSenderName() {
    // TODO Get this from the sender when the sender is implemented
    return "messagebird-voice";
  }

  @Override
  protected CompletableFuture<MessageResponse.Recipients> getRecipients(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return getRecipients(attemptPendingAnalysis.getRemoteId());
  }

  protected CompletableFuture<MessageResponse.Recipients> getRecipients(final String messageId) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return messageBirdClient.viewVoiceMessage(messageId).getRecipients();
      } catch (final MessageBirdException e) {
        if (!(e instanceof NotFoundException)) {
          logger.warn("Unexpected exception while analyzing attempt", e);
        }

        throw CompletionExceptions.wrap(e);
      }
    }, executor);
  }
}
