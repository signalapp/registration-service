package org.signal.registration.sender;


import com.google.i18n.phonenumbers.Phonenumber;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.EachBean;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.util.Pair;
import org.signal.registration.bandit.AdaptiveStrategy;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.fictitious.FictitiousNumberVerificationCodeSender;
import org.signal.registration.sender.prescribed.PrescribedVerificationCodeSender;
import org.signal.registration.util.MapUtil;
import org.signal.registration.util.PhoneNumbers;
import static org.signal.registration.sender.SenderSelectionStrategy.SenderSelection;
import static org.signal.registration.sender.SenderSelectionStrategy.SelectionReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  public static final String ADAPTIVE_NAME = "adaptive";

  private static final String ADAPTIVE_SAMPLING_COUNTER_NAME = MetricsUtil.name(DynamicSelector.class, "adaptiveSampling");

  private final MessageTransport transport;
  private final List<VerificationCodeSender> fallbackSenders;
  private final Optional<EnumeratedDistribution<String>> defaultDist;
  private final Map<String, EnumeratedDistribution<String>> regionalDist;
  private final Map<String, VerificationCodeSender> regionOverrides;
  private final Map<String, VerificationCodeSender> senders;
  private final AdaptiveStrategy strategy;
  private final MeterRegistry meterRegistry;

  public DynamicSelector(
      final RandomGenerator random,
      final MeterRegistry meterRegistry,
      final DynamicSelectorConfiguration config,
      final AdaptiveStrategy adaptiveStrategy,
      final List<VerificationCodeSender> verificationCodeSenders) {
    this.meterRegistry = meterRegistry;
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
            config.regionWeights().values().stream().flatMap(m -> m.keySet().stream())).flatMap(Function.identity());

    List<String> missingNames =
        names.filter(this::isUnknownSender).distinct().toList();

    if (!missingNames.isEmpty()) {
      throw new IllegalStateException("unknown senders: " + String.join(", ", missingNames));
    }

    // type of messages being sent (e.g. sms, voice)
    this.transport = config.transport();

    // specific sender to be used for a given region (2).
    this.regionOverrides = MapUtil.mapValues(config.regionOverrides(), senders::get);

    // weighted distribution of senders for a particular region (3a).
    this.regionalDist = MapUtil.filterMapValues(
        config.regionWeights(),
        v -> DynamicSelector.parseDistribution(transport, senders, random, v));

    // default weighted distribution of senders (3b).
    this.defaultDist = Optional.of(config.defaultWeights())
        .filter(weights -> !weights.isEmpty())
        .flatMap(weights -> parseDistribution(transport, senders, random, weights));

    // senders to use if nothing else is working (4, 5).
    this.fallbackSenders = config.fallbackSenders().stream().map(senders::get).toList();

    // adaptive strategy for message routing
    this.strategy = adaptiveStrategy;
  }


  private static boolean isProductionSender(VerificationCodeSender sender) {
    return !(sender instanceof FictitiousNumberVerificationCodeSender
        || sender instanceof PrescribedVerificationCodeSender);
  }

  private boolean isUnknownSender(String senderName) {
    return !senders.containsKey(senderName) && !senderName.equals(ADAPTIVE_NAME);
  }

  public SenderSelection chooseVerificationCodeSender(
    final Phonenumber.PhoneNumber phoneNumber,
    final List<Locale.LanguageRange> languageRanges,
    final ClientType clientType,
    final @Nullable String preferredSender,
    final Set<String> previouslyFailedSenders) {

    if (preferredSender != null && senders.containsKey(preferredSender)) {
      return new SenderSelection(senders.get(preferredSender), SelectionReason.PREFERRED);
    }

    final String region = PhoneNumbers.regionCodeUpper(phoneNumber);

    // check for region based overrides
    if (this.regionOverrides.containsKey(region)) {
      return new SenderSelection(this.regionOverrides.get(region), SelectionReason.CONFIGURED);
    }

    // determine what we would pick with the adaptive strategy
    final String adaptivePick = strategy.sample(transport, phoneNumber, region, languageRanges, clientType,
        previouslyFailedSenders);

    // make a weighted selection if we have one configured
    final Optional<SenderSelection> sampledSelection = Optional
        .ofNullable(this.regionalDist.get(region)).or(() -> this.defaultDist)
        .map(EnumeratedDistribution::sample)
        .map(name -> name.equals(ADAPTIVE_NAME)
            ? new SenderSelection(senders.get(adaptivePick), SelectionReason.ADAPTIVE)
            : new SenderSelection(senders.get(name), SelectionReason.RANDOM));

    final SenderSelection selection = findSupportedSender(sampledSelection, phoneNumber, languageRanges);
    final boolean usedAdaptive = selection.reason().equals(SelectionReason.ADAPTIVE);
    meterRegistry.counter(ADAPTIVE_SAMPLING_COUNTER_NAME,
        MetricsUtil.REGION_CODE_TAG_NAME, region,
        MetricsUtil.SENDER_TAG_NAME, adaptivePick,
        MetricsUtil.TRANSPORT_TAG_NAME, transport.name(),
        "enabled", Boolean.toString(usedAdaptive)).increment();

    return selection;
  }

  /**
   * Return the sender to use for the request, and why we selected it.
   *
   * If the proposed choice does not support the message, find the first fallback sender that does. If no fallback
   * senders support the request, use the first fallback sender.
   *
   * @return The sender to use and the reason why
   */
  private SenderSelection findSupportedSender(
      final Optional<SenderSelection> choice,
      final Phonenumber.PhoneNumber phoneNumber,
      final List<Locale.LanguageRange> languageRanges) {

    final Predicate<VerificationCodeSender> supports = sender ->
        languageRanges.isEmpty() || sender.supportsLanguage(transport, phoneNumber, languageRanges);

    // If we didn't make a choice, use the fallback sender
    if (choice.isEmpty() && supports.test(fallbackSenders.get(0))) {
      return new SenderSelection(fallbackSenders.get(0), SelectionReason.CONFIGURED);
    }

    // If we have a choice, and it supports the message, use that
    if (choice.isPresent() && supports.test(choice.get().sender())) {
      return choice.get();
    }

    // If our choice or first fallback didn't support the language, find the first fallback that does.
    // in this case, we'll note that we selected this sender for capability reasons
    for (VerificationCodeSender sender : fallbackSenders) {
      if (supports.test(sender)) {
        return new SenderSelection(sender, SelectionReason.LANGUAGE_SUPPORT);
      }
    }

    // No senders support the request. Use our first fallback sender.
    return new SenderSelection(
        fallbackSenders.get(0),
        choice.isPresent()
            // If we initially had an alternate choice, we fell back for capability reasons
            ? SelectionReason.LANGUAGE_SUPPORT
            // Otherwise, no senders support this message, so we're just using the configured sender
            : SelectionReason.CONFIGURED);
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
