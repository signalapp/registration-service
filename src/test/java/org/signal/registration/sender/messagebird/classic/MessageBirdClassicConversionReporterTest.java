/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.messagebird.classic;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.messagebird.MessageBirdService;
import com.messagebird.exceptions.GeneralException;
import com.messagebird.exceptions.UnauthorizedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.sender.messagebird.verify.MessageBirdVerifySender;
import org.signal.registration.session.RegistrationAttempt;
import org.signal.registration.session.RegistrationSession;
import org.signal.registration.session.SessionCompletedEvent;

class MessageBirdClassicConversionReporterTest {
  private final MessageBirdService mockService = mock(MessageBirdService.class);
  private final MessageBirdClassicConversionReporter reporter = new MessageBirdClassicConversionReporter(mockService,
      new SimpleMeterRegistry(), Runnable::run);

  @BeforeEach
  public void setup() {
    reset(mockService);
  }

  @Test
  public void reportSingleAttempt() throws GeneralException, UnauthorizedException {
    reporter.onApplicationEvent(new SessionCompletedEvent(RegistrationSession.newBuilder()
        .setVerifiedCode("abc")
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setRemoteId("a")
            .setSenderName(MessageBirdSmsSender.SENDER_NAME)
            .build())
        .build()));

    verify(mockService, times(1))
        .sendPayLoad(eq("/conversions"), eq(new MessageBirdClassicConversionReporter.ConversionRequest("sms", "a", true)), isNull());
    verifyNoMoreInteractions(mockService);
  }

  @Test
  public void reportMultiAttempt() throws GeneralException, UnauthorizedException {
    reporter.onApplicationEvent(new SessionCompletedEvent(RegistrationSession.newBuilder()
        .setVerifiedCode("abc")
        // attempt failed
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setRemoteId("a")
            .setSenderName(MessageBirdSmsSender.SENDER_NAME)
            .build())
        // attempt with non-classic sender
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setRemoteId("b")
            .setSenderName(MessageBirdVerifySender.SENDER_NAME)
            .build())
        // success with voice sender
        .addRegistrationAttempts(RegistrationAttempt.newBuilder()
            .setRemoteId("c")
            .setSenderName(MessageBirdVoiceSender.SENDER_NAME)
            .build())
        .build()));

    verify(mockService, times(1))
        .sendPayLoad(eq("/conversions"), eq(new MessageBirdClassicConversionReporter.ConversionRequest("sms", "a", false)), isNull());
    verify(mockService, times(1))
        .sendPayLoad(eq("/conversions"), eq(new MessageBirdClassicConversionReporter.ConversionRequest("voice", "c", true)), isNull());
    verifyNoMoreInteractions(mockService);
  }

}
