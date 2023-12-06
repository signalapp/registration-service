package org.signal.registration.sender.messagebird.classic;

import com.google.common.annotations.VisibleForTesting;
import com.messagebird.MessageBirdService;
import com.messagebird.MessageBirdServiceImpl;
import com.messagebird.exceptions.GeneralException;
import com.messagebird.exceptions.UnauthorizedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.messagebird.MessageBirdClientConfiguration;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Report success/failure outcomes of messagebird sms/voice messages to messagebird. Does not include messagebird-verify
 * outcomes, because those are delivered as part of checking verification codes.
 */
@Singleton
public class MessageBirdClassicConversionReporter implements ApplicationEventListener<SessionCompletedEvent> {

  private static final Logger logger = LoggerFactory.getLogger(MessageBirdClassicConversionReporter.class);

  private static final String COUNTER_NAME = MetricsUtil.name(MessageBirdClassicConversionReporter.class,
      "conversionPosted");

  private final MessageBirdService messageBirdService;
  private final Executor executor;
  private final MeterRegistry meterRegistry;

  public MessageBirdClassicConversionReporter(
      final MessageBirdClientConfiguration clientConfiguration,
      final MeterRegistry meterRegistry,
      final @Named(TaskExecutors.IO) Executor executor) {
    this(new MessageBirdServiceImpl(clientConfiguration.accessKey()), meterRegistry, executor);
  }

  @VisibleForTesting
  MessageBirdClassicConversionReporter(
      final MessageBirdService messageBirdService,
      final MeterRegistry meterRegistry,
      final Executor executor) {
    this.messageBirdService = messageBirdService;
    this.meterRegistry = meterRegistry;
    this.executor = executor;
  }


  /**
   * Request body for the messagebird /conversion endpoint
   *
   * @param service "voice" or "sms"
   * @param id      the message id
   * @param success whether the attempt was successfully converted
   */
  @VisibleForTesting
  record ConversionRequest(String service, String id, boolean success) {

  }

  @Override
  public void onApplicationEvent(final SessionCompletedEvent event) {
    final RegistrationSession session = event.session();

    for (int i = 0; i < session.getRegistrationAttemptsCount(); i++) {
      final RegistrationAttempt attempt = session.getRegistrationAttempts(i);

      // Assume that all verification attempts before the last one were not successfully verified
      final boolean attemptVerified = StringUtils.isNotBlank(session.getVerifiedCode()) &&
          i == session.getRegistrationAttemptsCount() - 1;

      messageBirdServiceType(attempt.getSenderName()).ifPresent(serviceType -> executor.execute(() -> {
        final ConversionRequest request = new ConversionRequest(serviceType, attempt.getRemoteId(), attemptVerified);
        try {
          messageBirdService.sendPayLoad("/conversions", request, null);
          meterRegistry.counter(COUNTER_NAME, MetricsUtil.SUCCESS_TAG_NAME, String.valueOf(true)).increment();
        } catch (UnauthorizedException | GeneralException e) {
          logger.warn("Failed to post conversion", e);
          meterRegistry.counter(COUNTER_NAME, MetricsUtil.SUCCESS_TAG_NAME, String.valueOf(false)).increment();
          throw new CompletionException(e);
        }
      }));
    }
  }

  /**
   * Get the service type for an attempt to provide in the conversion request
   *
   * @param senderName The senderName of the sender that processed the attempt
   * @return The serviceType to use in the request, or empty if this is not message bird sender that requires conversion
   * reporting
   */
  private static Optional<String> messageBirdServiceType(final String senderName) {
    return switch (senderName) {
      case MessageBirdSmsSender.SENDER_NAME -> Optional.of("sms");
      case MessageBirdVoiceSender.SENDER_NAME -> Optional.of("voice");
      default -> Optional.empty();
    };
  }
}
