package org.signal.registration.bandit;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.math3.random.AbstractRandomGenerator;
import org.apache.commons.math3.random.RandomGenerator;
import org.signal.registration.cost.CostProvider;
import org.signal.registration.sender.DynamicSelectorConfiguration;
import org.signal.registration.sender.MessageTransport;

/**
 * Creates and validates {@link AdaptiveStrategy}s based on configuration.
 * <p>
 * One independent AdaptiveStrategy will exist for each message transport. Note that we allow the user of the
 * AdaptiveStrategy to get their {@link AdaptiveStrategy} during their construction (rather than injecting it directly)
 * so they can provide their configuration parameters for validation.
 * <p>
 * Before creation, this class ensures that any region that is configured for adaptive routing is capable of fetching
 * cost information
 */
@Singleton
public class AdaptiveStrategyCreator {

  private static final RandomGenerator RANDOM = new AbstractRandomGenerator() {

    @Override
    public void setSeed(final long seed) {
      ThreadLocalRandom.current().setSeed(seed);
    }

    @Override
    public double nextDouble() {
      return ThreadLocalRandom.current().nextDouble();
    }
  };

  private final Map<MessageTransport, AdaptiveStrategyConfiguration> adaptiveConfigurations;
  private final CostProvider costProvider;
  private final VerificationStatsProvider verificationStatsProvider;

  public AdaptiveStrategyCreator(
      final List<AdaptiveStrategyConfiguration> adaptiveConfigurations,
      final CostProvider costProvider,
      final VerificationStatsProvider verificationStatsProvider) {
    this.adaptiveConfigurations = adaptiveConfigurations.stream().collect(Collectors.toMap(
        AdaptiveStrategyConfiguration::transport,
        Function.identity()));
    this.costProvider = costProvider;
    this.verificationStatsProvider = verificationStatsProvider;
  }

  /**
   * Create and validate configuration for an {@link AdaptiveStrategy}
   *
   * @param dynamicSelectorConfiguration indicates the configured adaptive regions and the message transport type
   * @return the new {@link AdaptiveStrategy}
   */
  public AdaptiveStrategy create(final DynamicSelectorConfiguration dynamicSelectorConfiguration) {
    if (!this.adaptiveConfigurations.containsKey(dynamicSelectorConfiguration.transport())) {
      throw new IllegalArgumentException(
          "no AdaptiveConfiguration found for transport " + dynamicSelectorConfiguration.transport());
    }
    final AdaptiveStrategyConfiguration config = this.adaptiveConfigurations.get(
        dynamicSelectorConfiguration.transport());

    // regions which may want to use adaptive routing
    final Set<String> adaptiveRegions = dynamicSelectorConfiguration.computeAdaptiveRegions();

    // check that these regions all are capable of getting cost information
    validateConfig(adaptiveRegions, costProvider, config);

    return new AdaptiveStrategy(config, costProvider, verificationStatsProvider, RANDOM);
  }

  /**
   * Validate that all regions configured for adaptive routing have cost information for their senders.
   * <p>
   * Note that we do not expect to have an exhaustive list of all possible regions. Instead, we should determine which
   * regions have adaptive routing configured and only test those regions. In the future we may want to make cost
   * scaling optional (or supply an exhaustive list of regions), in which case this logic would have to change.
   *
   * @param adaptiveRegions The list of regions to validate
   * @param costProvider    The configured costs for each region/sender combination.
   * @param config          The adaptive strategy configuration
   */
  private static void validateConfig(
      final Set<String> adaptiveRegions,
      final CostProvider costProvider,
      final AdaptiveStrategyConfiguration config) {
    final Map<String, Set<String>> missingCosts = findMissingCosts(
        adaptiveRegions,
        (region, senderName) -> costProvider.supports(config.transport(), region, senderName),
        config);
    if (!missingCosts.isEmpty()) {
      final String message = missingCosts.entrySet()
          .stream()
          .map(e -> "region '%s' missing costs for '%s'".formatted(e.getKey(), e.getValue()))
          .reduce("", (s, t) -> s + ", " + t);
      throw new IllegalArgumentException(message);
    }
  }

  /**
   * Find senders in adaptive regions that do not have cost information.
   *
   * @param adaptiveRegions The list of regions to validate
   * @param hasCostInfo     Function that takes the region and senderName and returns whether they have cost
   *                        information
   * @param config          The adaptive strategy configuration
   */
  @VisibleForTesting
  static Map<String, Set<String>> findMissingCosts(
      final Set<String> adaptiveRegions,
      final BiFunction<String, String, Boolean> hasCostInfo,
      final AdaptiveStrategyConfiguration config) {
    final Map<String, Set<String>> missingCosts = new HashMap<>();
    adaptiveRegions.forEach(region -> {
      final Set<String> regionalSenders = config.regionalChoices()
          .getOrDefault(region.toUpperCase(), config.defaultChoices());
      final Set<String> missingNames = regionalSenders.stream()
          .filter(senderName -> !hasCostInfo.apply(region, senderName))
          .collect(Collectors.toSet());
      if (!missingNames.isEmpty()) {
        missingCosts.put(region, missingNames);
      }
    });
    return missingCosts;
  }
}
