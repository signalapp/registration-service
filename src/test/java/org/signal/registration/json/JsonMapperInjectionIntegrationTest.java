package org.signal.registration.json;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.json.JsonMapper;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;
import org.signal.registration.ratelimit.RateLimiter;
import org.signal.registration.sender.SenderSelectionStrategy;

@MicronautTest
public class JsonMapperInjectionIntegrationTest {

  @MockBean
  SenderSelectionStrategy senderSelectionStrategy = mock(SenderSelectionStrategy.class);

  @SuppressWarnings("unchecked")
  @MockBean(named = "session-creation")
  @Named("session-creation")
  RateLimiter<Pair<Phonenumber.PhoneNumber, String>> sessionCreationRateLimiter = mock(RateLimiter.class);

  @SuppressWarnings("unchecked")
  @MockBean(named = "send-sms-verification-code-per-number")
  @Named("send-sms-verification-code-per-number")
  RateLimiter<Phonenumber.PhoneNumber> sendSmsVerificationCodePerNumberRateLimiter = mock(RateLimiter.class);

  @SuppressWarnings("unchecked")
  @MockBean(named = "send-voice-verification-code-per-number")
  @Named("send-voice-verification-code-per-number")
  RateLimiter<Phonenumber.PhoneNumber> sendVoiceVerificationCodePerNumberRateLimiter = mock(RateLimiter.class);

  @SuppressWarnings("unchecked")
  @MockBean(named = "check-verification-code-per-number")
  @Named("check-verification-code-per-number")
  RateLimiter<Phonenumber.PhoneNumber> checkVerificationCodePerNumberRateLimiter = mock(RateLimiter.class);

  @Inject
  JsonMapper jsonMapper;

  @Test
  void testJsonMapper() {
    // This won't actually fail - the failure will happen in the beforeEach that looks for the bean
    assertNotNull(jsonMapper, "a json mapper must be available for injection");
  }

}
