package org.signal.registration.bandit;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.math3.distribution.BetaDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.ClientType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class responsible for choosing verification code senders based on a multi-armed bandit strategy.
 * <p>
 * This class is responsible for combining the statistics returned by the BanditStatsProvider with the configuration
 * specified by {@link org.signal.registration.sender.DynamicSelectorConfiguration} in order to decide which sender to
 * use for a particular messages. When running a bandit we will choose from a pool of configured senders for that bandit
 * (which is configured globally with regional overrides).
 * <p>
 * See {@link org.signal.registration.sender.DynamicSelectorConfiguration} for more details, as well as the
 * <a href="https://en.wikipedia.org/wiki/Thompson_sampling">Wikipedia page for Thompson sampling</a>.
 */
public class AdaptiveStrategy {

  private static final Logger log = LoggerFactory.getLogger(AdaptiveStrategy.class);

  private static final String SAMPLING_COUNTER_NAME = MetricsUtil.name(AdaptiveStrategy.class, "sampling");
  private static final String REGION_TAG_NAME = "region";
  private static final String USE_REGIONAL_STATS_TAG_NAME = "useRegionalStats";
  private static final String CHOICE_TAG_NAME = "choice";

  public record Config(
      Optional<Double> minimumRegionalWeight,
      Set<String> defaultChoices,
      Map<String, Set<String>> regionalChoices) {}

  public record Choice(String name, double successes, double failures) {
    public static Choice empty(final String name) {
      return new Choice(name, 0.0, 0.0);
    }
    public double total() {
      return successes + failures;
    }
    public double sample(final RandomGenerator generator) {
      // beta distribution requires positive alpha and beta parameters.
      BetaDistribution beta = new BetaDistribution(generator, successes + 1.0, failures + 1.0);
      return beta.sample();
    }

    public Choice addSuccess(double count) {
      return new Choice(name, successes + count, failures);
    }
    public Choice addFailures(double count) {
      return new Choice(name, successes, failures + count);
    }
  }

  private final Config config;
  private final BanditStatsProvider statsProvider;
  private final RandomGenerator generator;
  private final MeterRegistry meterRegistry;

  public AdaptiveStrategy(
      final Config config,
      final BanditStatsProvider statsProvider,
      final RandomGenerator generator,
      final MeterRegistry meterRegistry) {
    this.config = config;
    this.statsProvider = statsProvider;
    this.generator = generator;
    this.meterRegistry = meterRegistry;
  }

  /**
   * Select the set of allowed choices, either using a regional override or the global setting.
   * @param region The region requesting a verification
   * @return the set of allowed sender names (which is guaranteed to be non-empty).
   */
  private Set<String> regionalOverrides(String region) {
    return config.regionalChoices.getOrDefault(region, config.defaultChoices);
  }

  /**
   * Validate a list of choices against the configured settings and regional overrides.
   * <p>
   * This method does two things:
   * (1) Ensures that any provided choice is allowed (either by the global configuration or a regional override)
   * (2) Ensures that configured senders which aren't already in the list are added (with zero counts).
   * The length of the resulting list should always be the same size as the configured set.
   * @param choices The list of provided choices
   * @param configured Set of configured sender names which are allowed
   * @return the list of valid choices.
   */
  private List<Choice> resolveChoices(final List<Choice> choices, final Set<String> configured) {
    final Set<String> seen = choices.stream().map(Choice::name).collect(Collectors.toSet());
    final Stream<Choice> allowedChoices = choices.stream().filter(c -> configured.contains(c.name()));
    final Stream<Choice> missingChoices = configured.stream().filter(s -> !seen.contains(s)).map(Choice::empty);
    return Stream.concat(allowedChoices, missingChoices).toList();
  }

  /**
   * Sample from this bandit, using all the regional and contextual policies we have configured.
   * <p>
   * The possible choices will be limited by the regional override (if any), or the global set of configured choices
   * otherwise. Regional weights will be used as long as they exceed the configured minimum (if any). Once the choices
   * and weights are computed one will be selected according to the logic in sampleChoices.
   * <p>
   * The minimum regional bandit weight describes the threshold at which we start limiting ourselves to regional
   * statistics (versus just choosing based on our global statistics). If we assume that our "globally-best" provider
   * will work best in most regions with low traffic, we should use a larger number (e.g. 50). If we assume that each
   * region is fairly distinct then we should use a small number (or leave it unset).
   *
   * @param phoneNumber the phone number to which a verification should be sent
   * @param region the region to which a verification should be sent
   * @param languageRanges the desired languages for the given verification
   * @param clientType which type of client is requesting a verification
   * @return the name of the sender which was selected
   */
  public String sample(
      final Phonenumber.PhoneNumber phoneNumber,
      final String region,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType
  ) {
    final List<Choice> rawRegionChoices = statsProvider.getRegionalChoices(phoneNumber, region);
    final List<Choice> regionChoices = resolveChoices(rawRegionChoices, regionalOverrides(region));
    final double regionTotal = regionChoices.stream().map(Choice::total).reduce(0.0, Double::sum);
    final boolean useRegionalBandit = config.minimumRegionalWeight.map(x -> regionTotal >= x).orElse(true);

    final List<Choice> choices = useRegionalBandit
        ? regionChoices
        : resolveChoices(statsProvider.getGlobalChoices(), config.defaultChoices);
    final String choice = sampleChoices(choices).name();

    log.debug("{} sampling for region {} returned choice {} (from {} choices)", useRegionalBandit ? "regional" : "global", region, choice, choices.size());

    meterRegistry.counter(SAMPLING_COUNTER_NAME,
        REGION_TAG_NAME, region,
        USE_REGIONAL_STATS_TAG_NAME, String.valueOf(useRegionalBandit),
        CHOICE_TAG_NAME, choice).increment();
    return choice;
  }

  /**
   * Sample from a list of choices using the beta distribution to compute a score for each choice.
   *
   * @param choices The list of choices (names and weights) to sample from.
   * @return The selected choice
   */
  @VisibleForTesting
  Choice sampleChoices(final List<Choice> choices) {
    if (choices.isEmpty()) {
      throw new IllegalArgumentException("can't sample from empty choices");
    }
    final Iterator<Choice> it = choices.iterator();
    Choice bestChoice = it.next();
    double bestScore = bestChoice.sample(generator);
    while (it.hasNext()) {
      final Choice choice = it.next();
      final double score = choice.sample(generator);
      if (score > bestScore) {
        bestChoice = choice;
        bestScore = score;
      }
    }
    return bestChoice;
  }

}
