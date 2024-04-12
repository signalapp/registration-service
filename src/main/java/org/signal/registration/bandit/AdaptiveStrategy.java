package org.signal.registration.bandit;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.Phonenumber;
import jakarta.inject.Singleton;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.math3.random.RandomGenerator;
import org.signal.registration.cost.CostProvider;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class responsible for choosing verification code senders based on a multi-armed bandit strategy.
 * <p>
 * This class is responsible for combining the statistics returned by the {@link VerificationStatsProvider} with the configuration
 * specified by {@link AdaptiveStrategyConfiguration} in order to decide which sender to use for a particular messages.
 * When running a bandit we will choose from a pool of configured senders for that bandit (which is configured globally
 * with regional overrides).
 * <p>
 * See {@link AdaptiveStrategyConfiguration} for more details, as well as the
 * <a href="https://en.wikipedia.org/wiki/Thompson_sampling">Wikipedia page for Thompson sampling</a>.
 */
@Singleton
public class AdaptiveStrategy {

  private static final Logger log = LoggerFactory.getLogger(AdaptiveStrategy.class);

  private final Map<MessageTransport, AdaptiveStrategyConfiguration> configs;
  private final CostProvider costProvider;
  private final VerificationStatsProvider statsProvider;
  private final RandomGenerator generator;

  public AdaptiveStrategy(
      final List<AdaptiveStrategyConfiguration> config,
      final CostProvider costProvider,
      final VerificationStatsProvider statsProvider,
      final RandomGenerator generator) {
    this.configs = config.stream().collect(Collectors.toMap(AdaptiveStrategyConfiguration::transport, Function.identity()));
    this.costProvider = costProvider;
    this.statsProvider = statsProvider;
    this.generator = generator;
  }

  /**
   * Select the set of allowed choices, either using a regional override or the global setting.
   *
   * @param messageTransport the message transport to use
   * @param region The region requesting a verification
   * @return the set of allowed sender names (which is guaranteed to be non-empty).
   */
  private Set<String> configuredSenders(final MessageTransport messageTransport, String region) {
    final AdaptiveStrategyConfiguration config = configs.get(messageTransport);
    return config.regionalChoices().getOrDefault(region, config.defaultChoices());
  }


  /**
   * Sample from this bandit, using all the regional and contextual policies we have configured.
   * <p>
   * The possible choices will be limited by the regional override (if any), or the global set of configured choices
   * otherwise.  Once the choices and weights are computed one will be selected according to the logic in sampleChoices.
   *
   * @param phoneNumber    the phone number to which a verification should be sent
   * @param region         the region to which a verification should be sent
   * @param languageRanges the desired languages for the given verification
   * @param clientType     which type of client is requesting a verification
   * @param failedSenders  names of senders we've previously tried and failed to verify with
   * @return the name of the sender which was selected
   */
  public String sample(
      final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final String region,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType,
      final Set<String> failedSenders) {

    final List<Distribution> choices = buildDistributions(messageTransport, region, phoneNumber, failedSenders);
    final String choice = sampleChoices(generator, choices).senderName();

    log.debug("sampling for region {} returned choice {} (from {} choices)", region, choice, choices.size());
    return choice;
  }

  private Set<String> candidateSenders(final MessageTransport messageTransport, final String region, Set<String> failedSenders) {
    // Get the configured list of senders for this region
    final Set<String> configuredSenders = configuredSenders(messageTransport, region);
    if (failedSenders.isEmpty()) {
      return configuredSenders;
    }

    // remove senders that have previous had a failed attempt in the session
    final Set<String> sendersWithoutFailedAttempts = configuredSenders.stream()
        .filter(sender -> !failedSenders.contains(sender))
        .collect(Collectors.toSet());

    // If there is at least one candidate that hasn't previously failed, consider only senders without failures.
    // Otherwise, use the configured senders.
    return sendersWithoutFailedAttempts.isEmpty() ? configuredSenders : sendersWithoutFailedAttempts;
  }

  @VisibleForTesting
  List<Distribution> buildDistributions(
      final MessageTransport messageTransport,
      final String region,
      final Phonenumber.PhoneNumber phoneNumber,
      final Set<String> failedSenders) {
    final Set<String> candidates = candidateSenders(messageTransport, region, failedSenders);

    // Get success/failure information about each sender
    final Map<String, VerificationStats> choices = candidates.stream()
        .collect(Collectors.toMap(
            Function.identity(),
            sender -> statsProvider
                .getVerificationStats(messageTransport, phoneNumber, region, sender)
                .orElseGet(VerificationStats::empty)));

    // Get cost information about each sender
    final Map<String, Optional<Double>> costs = candidates.stream()
        .collect(Collectors.toMap(
            Function.identity(),
            sender -> costProvider.getCost(messageTransport, region, sender).map(Integer::doubleValue)));

    // Build distributions, computing the relative cost of each sender.
    // If none of the senders have cost information available, assign them all a cost of 1.0
    final double maxCost = costs.values().stream().flatMap(Optional::stream).max(Double::compareTo).orElse(1.0);
    return candidates.stream()
        .map(sender -> new Distribution(
            sender,
            choices.get(sender),
            costs.get(sender).orElse(maxCost) / maxCost,
            configs.get(messageTransport).costCoefficient().doubleValue()))
        .toList();

  }

  /**
   * Sample from a list of choices using the beta distribution to compute a score for each choice.
   *
   * @param choices The list of choices (names and weights) to sample from.
   * @return The selected choice
   */
  @VisibleForTesting
  public static Distribution sampleChoices(final RandomGenerator generator, final List<Distribution> choices) {
    if (choices.isEmpty()) {
      throw new IllegalArgumentException("can't sample from empty choices");
    }
    final Iterator<Distribution> it = choices.iterator();
    Distribution bestChoice = it.next();
    double bestScore = bestChoice.sample(generator);
    while (it.hasNext()) {
      final Distribution choice = it.next();
      final double score = choice.sample(generator);
      if (score > bestScore) {
        bestChoice = choice;
        bestScore = score;
      }
    }
    return bestChoice;
  }

}
