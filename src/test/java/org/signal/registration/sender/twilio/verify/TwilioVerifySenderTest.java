/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.twilio.verify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import com.twilio.exception.ApiException;
import com.twilio.http.TwilioRestClient;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Assert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.signal.registration.ratelimit.RateLimitExceededException;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;

class TwilioVerifySenderTest {
  private static final int MAX_RETRIES = 5;

  private final TwilioRestClient twilioRestClient = mock(TwilioRestClient.class);
  private TwilioVerifySender twilioVerifySender;

  @BeforeEach
  void setUp() {
    final TwilioVerifyConfiguration configuration =
        new TwilioVerifyConfiguration("service-sid", "friendly-name", "app-hash", null, List.of("en"), List.of("en"));

    twilioVerifySender = new TwilioVerifySender(
        new SimpleMeterRegistry(),
        twilioRestClient,
        configuration,
        mock(ApiClientInstrumenter.class),
        Duration.ofMillis(1),
        MAX_RETRIES);
  }


  @Test
  public void apiRetires() {
    final int successTries = 3;
    final AtomicInteger callCounter = new AtomicInteger();
    twilioVerifySender.withRetries(() -> {
      if (callCounter.incrementAndGet() == successTries) {
        return CompletableFuture.completedFuture(null);
      }
      throw new ApiException("test", 20429, "", 200, null);
    }, "test").toCompletableFuture().join();
    assertEquals(callCounter.get(), successTries);
  }

  @Test
  public void maxRetriesExceeded() throws Exception {
    final AtomicInteger callCounter = new AtomicInteger();
    when(twilioRestClient.request(any())).then(
        ignored -> {
          callCounter.incrementAndGet();
          throw new ApiException("test", 20429, "", 200, null);
        });

    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().parse("+12025550123", null);
    final CompletionException exception = assertThrows(
        CompletionException.class,
        () -> twilioVerifySender.sendVerificationCode(
            MessageTransport.VOICE, phoneNumber, Locale.LanguageRange.parse("en"), ClientType.IOS).join());
    assertInstanceOf(RateLimitExceededException.class, exception.getCause());
    assertEquals(1 + MAX_RETRIES, callCounter.get());
  }

  @Test
  public void nonretriableCodesAreNotRetried() {
    final AtomicBoolean called = new AtomicBoolean(false);
    final CompletionException exception = assertThrows(CompletionException.class,
        () -> twilioVerifySender.withRetries(() -> {
          if (called.getAndSet(true)) {
            Assert.fail("method should not be retired");
          }
          throw new ApiException("test", 20404, "", 200, null);
        }, "test").toCompletableFuture().join());
    assertInstanceOf(ApiException.class, exception.getCause());
  }


  @ParameterizedTest
  @MethodSource
  void supportsDestination(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType,
      final boolean expectSupportsDestination) {

    assertEquals(expectSupportsDestination,
        twilioVerifySender.supportsLanguage(messageTransport, phoneNumber, languageRanges));
  }

  private static Stream<Arguments> supportsDestination() throws NumberParseException {
    final Phonenumber.PhoneNumber phoneNumber =
        PhoneNumberUtil.getInstance().parse("+12025550123", null);

    return Stream.of(
        Arguments.of(MessageTransport.SMS, phoneNumber, Locale.LanguageRange.parse("en"), ClientType.IOS, true),
        Arguments.of(MessageTransport.VOICE, phoneNumber, Locale.LanguageRange.parse("en"), ClientType.IOS, true),
        Arguments.of(MessageTransport.SMS, phoneNumber, Locale.LanguageRange.parse("en"),
            ClientType.ANDROID_WITHOUT_FCM, true),
        Arguments.of(MessageTransport.SMS, phoneNumber, Locale.LanguageRange.parse("en"), ClientType.ANDROID_WITH_FCM,
            true),
        Arguments.of(MessageTransport.SMS, phoneNumber, Locale.LanguageRange.parse("en"), ClientType.UNKNOWN, true),
        Arguments.of(MessageTransport.SMS, phoneNumber, Locale.LanguageRange.parse("ja"), ClientType.IOS, false),
        Arguments.of(MessageTransport.VOICE, phoneNumber, Locale.LanguageRange.parse("ja"), ClientType.IOS, false),
        Arguments.of(MessageTransport.SMS, phoneNumber, Locale.LanguageRange.parse("ja,en;q=0.4"), ClientType.IOS, true),
        Arguments.of(MessageTransport.VOICE, phoneNumber, Locale.LanguageRange.parse("ja,en;q=0.4"), ClientType.IOS, true));
  }
}
