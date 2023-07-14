package org.signal.registration.bandit;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import org.signal.registration.Environments;
import java.util.List;
import java.util.Map;

@Singleton
@Requires(env = {Environments.DEVELOPMENT, Environment.TEST}, missingBeans = BanditStatsProvider.class)
public class InMemoryBanditStatsProvider implements BanditStatsProvider {

  private Map<String, List<AdaptiveStrategy.Choice>> regionalChoices;
  private List<AdaptiveStrategy.Choice> globalChoices;

  public InMemoryBanditStatsProvider() {
    update(Map.of());
  }

  public static InMemoryBanditStatsProvider create(final Map<String, List<AdaptiveStrategy.Choice>> regions) {
    InMemoryBanditStatsProvider imbsp = new InMemoryBanditStatsProvider();
    imbsp.update(regions);
    return imbsp;
  }

  public void update(final Map<String, List<AdaptiveStrategy.Choice>> regions) {
    regionalChoices = regions;
    globalChoices = BanditStatsProvider.computeGlobalChoices(regions);
  }

  @Override
  public List<AdaptiveStrategy.Choice> getRegionalChoices(
      final Phonenumber.PhoneNumber phoneNumber,
      final String region) {
    final List<AdaptiveStrategy.Choice> choices = regionalChoices.get(region);
    return choices == null ? List.of() : choices;
  }

  @Override
  public List<AdaptiveStrategy.Choice> getGlobalChoices() {
    return globalChoices == null ? List.of() : globalChoices;
  }
}
