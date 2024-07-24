package org.signal.registration.json;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.json.JsonMapper;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
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
  RateLimiter<Phonenumber.PhoneNumber> sessionCreationRateLimiter = mock(RateLimiter.class);

  @Inject
  JsonMapper jsonMapper;

  @Test
  void testJsonMapper() {
    // This won't actually fail - the failure will happen in the beforeEach that looks for the bean
    assertNotNull(jsonMapper, "a json mapper must be available for injection");
  }

}
