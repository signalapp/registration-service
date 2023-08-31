package org.signal.registration.bandit;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import org.signal.registration.Environments;
import org.signal.registration.sender.MessageTransport;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

@Singleton
@Requires(env = {Environments.DEVELOPMENT, Environment.TEST}, missingBeans = VerificationStatsProvider.class)
public class InMemoryVerificationStatsProvider implements VerificationStatsProvider {
  private Map<String, Map<String, VerificationStats>> senderStatsByRegion = Collections.emptyMap();

  public static InMemoryVerificationStatsProvider create(final Map<String, Map<String, VerificationStats>> regions) {
    InMemoryVerificationStatsProvider imbsp = new InMemoryVerificationStatsProvider();
    imbsp.update(regions);
    return imbsp;
  }

  public void update(final Map<String, Map<String, VerificationStats>> regions) {
    senderStatsByRegion = regions;
  }


  @Override
  public Optional<VerificationStats> getVerificationStats(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber, final String region, final String senderName) {
    return Optional.ofNullable(senderStatsByRegion.getOrDefault(region, Collections.emptyMap()).get(senderName));
  }
}
