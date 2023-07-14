package org.signal.registration.sender;

import com.google.common.annotations.VisibleForTesting;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.EachBean;
import jakarta.inject.Singleton;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.random.AbstractRandomGenerator;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.util.Pair;
import org.signal.registration.bandit.BanditStatsProvider;
import org.signal.registration.sender.fictitious.FictitiousNumberVerificationCodeSender;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeSender;
import org.signal.registration.bandit.AdaptiveStrategy;
import org.signal.registration.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;

/**
 * Selects a sender for a specific {@link MessageTransport}
 * <p>
 * Attempts to find the sender according to these prioritized rules:
 * <p>
 *   1. If we should always use a specific sender for the phone number, use that
 *   2. If we should always use a specific sender for the region, use that
 *   3. Do weighted random selection, and if the selection supports the request, use that
 *   4. Iterate through fallbacks and select the first sender that supports the request
 *   5. Use the first fallback sender
 */
@EachBean(DynamicSelectorConfiguration.class)
@Singleton
public class DynamicSelector {
  private static final Logger logger = LoggerFactory.getLogger(DynamicSelector.class);

  private static final String ADAPTIVE_NAME = "adaptive";

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

  private final MessageTransport transport;
  private final List<String> fallbackSenders;
  private final Optional<EnumeratedDistribution<String>> defaultDist;
  private final Map<String, EnumeratedDistribution<String>> regionalDist;
  private final Map<String, String> regionOverrides;
  private final Map<String, VerificationCodeSender> senders;
  private final AdaptiveStrategy strategy;

  public DynamicSelector(
      final DynamicSelectorConfiguration config,
      final List<VerificationCodeSender> verificationCodeSenders,
      final BanditStatsProvider banditStatsProvider,
      final MeterRegistry meterRegistry) {
    this(RANDOM, config, verificationCodeSenders, banditStatsProvider, meterRegistry);
  }

  @VisibleForTesting
  DynamicSelector(
      final RandomGenerator random,
      final DynamicSelectorConfiguration config,
      final List<VerificationCodeSender> verificationCodeSenders,
      final BanditStatsProvider banditStatsProvider,
      final MeterRegistry meterRegistry
  ) {

    logger.info("Configuring WeightedSelector for transport {} with {}", config.transport(), config);

    // look up senders by name
    this.senders = verificationCodeSenders
        .stream()
        .filter(DynamicSelector::isProductionSender)
        .collect(Collectors.toMap(VerificationCodeSender::getName, v -> v));

    // validate configuration to ensure we have verification code senders for all names.
    Stream<String> names =
        Stream.of(
            config.fallbackSenders().stream(),
            config.defaultWeights().keySet().stream(),
            config.regionOverrides().values().stream(),
            config.regionWeights().values().stream().flatMap(m -> m.keySet().stream())).flatMap(x -> x);

    List<String> missingNames =
        names.filter(this::isUnknownSender).distinct().toList();

    if (!missingNames.isEmpty()) {
      throw new IllegalStateException("unknown senders: " + String.join(", ", missingNames));
    }

    // type of messages being sent (e.g. sms, voice)
    this.transport = config.transport();

    // specific sender to be used for a given region (2).
    this.regionOverrides = parseAndNormalizeMap(config.regionOverrides(), Optional::of);

    // weighted distribution of senders for a particular region (3a).
    this.regionalDist = parseAndNormalizeMap(config.regionWeights(), v -> parseDistribution(transport, senders, random, v));

    // default weighted distribution of senders (3b).
    this.defaultDist = Optional.of(config.defaultWeights())
        .filter(weights -> !weights.isEmpty())
        .flatMap(weights -> parseDistribution(transport, senders, random, weights));

    // senders to use if nothing else is working (4, 5).
    this.fallbackSenders = config.fallbackSenders();

    // adaptive strategy for message routing
    this.strategy = new AdaptiveStrategy(
        parseAdaptiveConfig(config),
        banditStatsProvider,
        random,
        meterRegistry);
  }

  private static AdaptiveStrategy.Config parseAdaptiveConfig(DynamicSelectorConfiguration config) {
    final Optional<Double> minimumRegionalCount = config.minimumRegionalAdaptiveWeight().map(Integer::doubleValue);
    final Set<String> defaultChoices = new HashSet<>(config.defaultAdaptiveChoices());
    final Map<String, Set<String>> regionalChoices =
        parseAndNormalizeMap(config.regionalAdaptiveChoices(), v -> v.isEmpty() ? Optional.empty() : Optional.of(new HashSet<>(v)));
    return new AdaptiveStrategy.Config(minimumRegionalCount, defaultChoices, regionalChoices);
  }

  private static boolean isProductionSender(VerificationCodeSender sender) {
    return !(sender instanceof FictitiousNumberVerificationCodeSender
        || sender instanceof PrescribedVerificationCodeSender);
  }

  private boolean isUnknownSender(String senderName) {
    return !senders.containsKey(senderName) && !senderName.equals(ADAPTIVE_NAME);
  }

  private static <V, W> Map<String, W> parseAndNormalizeMap(Map<String, V> input, Function<V, Optional<W>> fn) {
    return input.entrySet().stream()
        .flatMap(e -> {
          final String k = e.getKey().toUpperCase();
          return fn.apply(e.getValue()).map(w -> new Pair<>(k, w)).stream();
        })
        .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
  }

  public VerificationCodeSender chooseVerificationCodeSender(
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges,
      final ClientType clientType,
      final @Nullable String preferredSender
  ) {
    return senders.get(chooseVerificationCodeSenderName(phoneNumber, languageRanges, clientType, preferredSender));
  }

  public String chooseVerificationCodeSenderName(
    final Phonenumber.PhoneNumber phoneNumber,
    final List<Locale.LanguageRange> languageRanges,
    final ClientType clientType,
    final @Nullable String preferredSender
  ) {

    if (preferredSender != null && senders.containsKey(preferredSender)) {
      return preferredSender;
    }

    // check for region based overrides
    final String region = PhoneNumbers.regionCodeUpper(phoneNumber);
    if (this.regionOverrides.containsKey(region)) {
      return this.regionOverrides.get(region);
    }

    // make a weighted selection if we have one configured
    final Optional<String> weightedSelection = Optional
        .ofNullable(this.regionalDist.get(region)).or(() -> this.defaultDist)
        .map(EnumeratedDistribution::sample);

    final Optional<String> sampledSelection =
        weightedSelection.map(name -> {
          if (name.equals(ADAPTIVE_NAME)) {
            return strategy.sample(phoneNumber, region, languageRanges, clientType);
          } else {
            return name;
          }
        });

    return Stream
      // [selected sender, fallbackSenders...]
      .concat(sampledSelection.stream(), this.fallbackSenders.stream())
      // get first sender that supports the destination
      .filter(s -> senders.get(s).supportsLanguageAndClient(transport, phoneNumber, languageRanges, clientType))
      .findFirst()
      // or, if none support the destination, the first fallbackSender
      .orElse(this.fallbackSenders.get(0));
  }

  public MessageTransport getTransport() {
    return transport;
  }

  /**
   * Build an {@link EnumeratedDistribution} of senders from a weighted configuration
   *
   * @throws IllegalStateException if a sender string doesn't correspond to any provided sender
   */
  private static Optional<EnumeratedDistribution<String>> parseDistribution(
      final MessageTransport transport,
      final Map<String, VerificationCodeSender> senders,
      final RandomGenerator random,
      final Map<String, Integer> weights
  ) throws IllegalStateException {
    final List<Pair<String, Double>> pairs =
        weights
          .entrySet().stream()
          .filter(e -> e.getValue() > 0) // drop senders with 0 weight
          .filter(e -> e.getKey().equals(ADAPTIVE_NAME) || senders.get(e.getKey()).supportsTransport(transport)) // drop unsupported senders
          .map(e -> Pair.create(e.getKey(), e.getValue().doubleValue())) // convert weights to double
          .toList();
    if (pairs.isEmpty()) {
      return Optional.empty();
    } else {
      return Optional.of(new EnumeratedDistribution<>(random, pairs));
    }
  }
}
