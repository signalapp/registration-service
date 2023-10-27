package org.signal.registration.bandit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.signal.registration.cost.FixedCostConfiguration;
import org.signal.registration.cost.FixedCostProvider;
import org.signal.registration.sender.MessageTransport;

public class AdaptiveStrategyTest {

  private final static boolean DETERMINISTIC = true;
  private final Map<String, Map<String, VerificationStats>> stats;
  private final JDKRandomGenerator generator;

  public AdaptiveStrategyTest() {

    stats = Map.of(
        // simulates a modest amount of registrations
        "R1", Map.of(
            "a", new VerificationStats(45.0, 5.0),
            "b", new VerificationStats(20.0, 5.0),
            "c", new VerificationStats(7.5, 15.0)),
        // simulates a cold start
        "R2", Map.of(
            "a", new VerificationStats(0.0, 0.0),
            "b", new VerificationStats(0.0, 0.0),
            "c", new VerificationStats(0.0, 0.0)),
        // simulates a country with low registration rates
        "R3", Map.of(
            "a", new VerificationStats(0.1, 0.05),
            "b", new VerificationStats(0.2, 0.2),
            "c", new VerificationStats(0.05, 0.1)),
        // derived from RU registration fraud data (24h half-life, 14 day window)
        "R4", Map.of(
            "a", new VerificationStats(4201.69687, 424.43773),
            "b", new VerificationStats(3545.6690, 805.458),
            "c", new VerificationStats(0.0, 0.0)));

    generator = new JDKRandomGenerator();
  }

  void maybeSeed(final int seed) {
    if (DETERMINISTIC) {
      generator.setSeed(seed);
    }
  }

  Map<String, Integer> computeHistogram(final Map<String, VerificationStats> choices, final int samples) {
    final List<Distribution> dists = choices.entrySet().stream()
        .map(c -> new Distribution(c.getKey(), c.getValue(), 1.0, 0.0))
        .toList();
    final Map<String, Integer> histogram = new HashMap<>();
    for (int j = 0; j < samples; j++) {
      final String name = AdaptiveStrategy.sampleChoices(generator, dists).senderName();
      histogram.put(name, histogram.getOrDefault(name, 0) + 1);
    }
    return histogram;
  }

  // 1M trials (100 samples per trial):
  // * 71 <= a <= 100
  // *  0 <= b <= 29
  // *  0 <= c <=  1
  @Test
  void testDistributionR1() {
    maybeSeed(0xabcd1357);
    final Map<String, Integer> histogram = computeHistogram(stats.get("R1"), 100);
    int a = histogram.getOrDefault("a", 0);
    int b = histogram.getOrDefault("b", 0);
    int c = histogram.getOrDefault("c", 0);
    assertTrue(71 <= a && a <= 100);
    assertTrue( 0 <= b && b <=  29);
    assertTrue( 0 <= c && c <=   2);
    assertEquals(100, a + b + c);
  }

  // 1M trials (100 samples per trial):
  // * 12 <= a <= 58
  // * 12 <= b <= 58
  // * 12 <= c <= 58
  @Test
  void testDistributionR2() {
    maybeSeed(0xabcd1357);
    final Map<String, Integer> histogram = computeHistogram(stats.get("R2"), 100);
    int a = histogram.getOrDefault("a", 0);
    int b = histogram.getOrDefault("b", 0);
    int c = histogram.getOrDefault("c", 0);
    assertTrue(11 <= a && a <= 59);
    assertTrue(11 <= b && b <= 59);
    assertTrue(11 <= c && c <= 59);
    assertEquals(100, a + b + c);
  }

  // 1M trials (100 samples per trial):
  // * 14 <= a <= 63
  // * 12 <= b <= 55
  // * 12 <= c <= 58
  @Test
  void testDistributionR3() {
    maybeSeed(0xabcd1357);
    final Map<String, Integer> histogram = computeHistogram(stats.get("R3"), 100);
    int a = histogram.getOrDefault("a", 0);
    int b = histogram.getOrDefault("b", 0);
    int c = histogram.getOrDefault("c", 0);
    assertTrue(13 <= a && a <= 64);
    assertTrue(11 <= b && b <= 56);
    assertTrue(11 <= c && c <= 59);
    assertEquals(100, a + b + c);
  }

  // 1M trials (100 samples per trial):
  // * 75 <= a <= 100
  // *  0 <= b <=  0
  // *  0 <= c <= 25
  @Test
  void testDistributionR4() {
    maybeSeed(0xabcd1357);
    final Map<String, Integer> histogram = computeHistogram(stats.get("R4"), 100);
    int a = histogram.getOrDefault("a", 0);
    int b = histogram.getOrDefault("b", 0);
    int c = histogram.getOrDefault("c", 0);
    assertTrue(74 <= a && a <= 100);
    assertTrue(0 <= b && b <= 1);
    assertTrue(0 <= c && c <= 26);
    assertEquals(100, a + b + c);
  }

  void generateStatsForTrials1M() {
    final String distribution = "R1";

    // print the min and max number of selections in 1M trials of 100 samples
    final Map<String, VerificationStats> choices = stats.get(distribution);
    final Map<String, IntSummaryStatistics> aggregated = IntStream
        .range(0, 100000)
        .mapToObj(i -> computeHistogram(choices, 100))
        .flatMap(m -> m.entrySet().stream())
        .collect(Collectors.groupingBy(Map.Entry::getKey, Collectors.summarizingInt(Map.Entry::getValue)));
    for (Map.Entry<String, IntSummaryStatistics> e : aggregated.entrySet()) {
      System.out.println("%s-min=%d, %s-max=%d".formatted(
          e.getKey(), e.getValue().getMin(),
          e.getKey(), e.getValue().getMax()));
    }
  }

  Map<String, Integer> computeConvergence(
      final Map<String, Double> probabilities,
      final HashMap<String, Distribution> choiceMap,
      final int trials) {

    final Map<String, Integer> histogram = new HashMap<>();
    for (int i = 0; i < trials; i++) {
      final List<Distribution> choices = choiceMap.values().stream().toList();
      final Distribution c = AdaptiveStrategy.sampleChoices(generator, choices);
      histogram.put(c.senderName(), histogram.getOrDefault(c.senderName(), 0) + 1);

      final double p = probabilities.get(c.senderName());
      final boolean success = generator.nextDouble() <= p;
      final Distribution u = success ? addSuccesses(c, 1.0) : addFailures(c, 1.0);
      choiceMap.put(u.senderName(), u);
    }
    return histogram;
  }

  @Test
  void testConvergence() {
    maybeSeed(0xabcd1357);
    final Map<String, Double> probabilities = Map.of("a", 0.85, "b", 0.9, "c", 0.75);
    final HashMap<String, Distribution> choiceMap = new HashMap<>();
    probabilities.forEach((k, v) -> choiceMap.put(k, Distribution.empty(k)));
    int samples = 100;
    int trials = 1000;
    int optimalCount = 0;
    int totalCount = 0;
    for (int i = 0; i < samples; i++) {
      Map<String, Integer> histogram = computeConvergence(probabilities, choiceMap, trials);
      totalCount += trials;
      optimalCount += histogram.getOrDefault("b", 0);
    }
    System.out.println(optimalCount);
    System.out.println(totalCount);
    assertEquals(samples * trials, totalCount);
    assertTrue(optimalCount >= totalCount * 0.75);
  }

  @Test
  void testConvergenceAfterShift() {
    maybeSeed(0xabcd1357);

    final int samples = 100;
    final int trials = 1000;
    final HashMap<String, Distribution> choiceMap = new HashMap<>();

    int optimalCount = 0;
    int totalCount = 0;
    int shiftOptimalCount = 0;
    int shiftTotalCount = 0;

    for (int i = 0; i < samples; i++) {
      // start with first distribution
      Map<String, Double> probabilities = Map.of("a", 0.85, "b", 0.9, "c", 0.75);
      probabilities.forEach((k, v) -> choiceMap.put(k, Distribution.empty(k)));
      Map<String, Integer> histogram = computeConvergence(probabilities, choiceMap, trials);
      totalCount += trials;
      optimalCount += histogram.getOrDefault("b", 0);
      // shift distribution now
      probabilities = Map.of("a", 0.9, "b", 0.7, "c", 0.75);
      histogram = computeConvergence(probabilities, choiceMap, trials);
      shiftTotalCount += trials;
      shiftOptimalCount += histogram.getOrDefault("a", 0);
    }

    assertEquals(samples * trials, totalCount);
    assertTrue(optimalCount >= totalCount * 0.75);
    assertEquals(samples * trials, shiftTotalCount);
    assertTrue(shiftOptimalCount >= shiftTotalCount * 0.55);
  }

  Map<String, Integer> computeSteadyStateHistogram(final Duration pause, final Duration halfLife, final int trials) {
    final List<String> names = List.of("a", "b", "c");
    final HashMap<String, Double> successRate = new HashMap<>();
    final HashMap<String, Double> failureRate = new HashMap<>();

    // calculate how much decay occurs during each pause
    final double scale = Math.exp(-Math.log(2.0) * pause.toMillis() / halfLife.toMillis());

    Map<String, Double> probabilities = Map.of("a", 0.85, "b", 0.9, "c", 0.75);
    final Map<String, Integer> histogram = new HashMap<>();

    double prevSize = 0.0;
    double currSize = 0.0;

    while (currSize >= prevSize) {
      prevSize = currSize;

      // decay previous stats
      successRate.replaceAll((k, v) -> v * scale);
      failureRate.replaceAll((k, v) -> v * scale);

      // compute choice probabilities and choose
      final List<Distribution> choices = names.stream()
          .map(s -> new Distribution(
              s,
              successRate.getOrDefault(s, 0.0),
              failureRate.getOrDefault(s, 0.0),
              1.0,
              0.0))
          .toList();
      final Distribution c = AdaptiveStrategy.sampleChoices(generator, choices);
      final String name = c.senderName();

      // update histogram on our choice
      histogram.put(name, histogram.getOrDefault(name, 0) + 1);

      // handle success and failure
      final double p = probabilities.get(c.senderName());
      final boolean success = generator.nextDouble() <= p;
      if (success) {
        successRate.put(name, successRate.getOrDefault(name, 0.0) + 1.0);
      } else {
        failureRate.put(name, failureRate.getOrDefault(name, 0.0) + 1.0);
      }
      currSize = successRate.values().stream().mapToDouble(x -> x).sum() + failureRate.values().stream().mapToDouble(x -> x).sum();
    }

    return histogram;
  }

  // INTERVAL  REQ/HALF-LIFE  HISTOGRAM
  //     6s        1200       {a=707, b=9201, c=172}
  //    12s         600       {a=1596, b=8198, c=286}
  //    30s         240       {a=1489, b=8158, c=433}
  //     1m         120       {a=2253, b=7161, c=666}
  //     2m          60       {a=2584, b=6731, c=765}
  //     5m          24       {a=2617, b=6222, c=1241}
  //    10m          12       {a=3272, b=5088, c=1720}
  @Disabled
  void testSteadyState() {
    System.out.println(computeSteadyStateHistogram(Duration.ofSeconds(6), Duration.ofMinutes(120), 10080));
    System.out.println(computeSteadyStateHistogram(Duration.ofSeconds(12), Duration.ofMinutes(120), 10080));
    System.out.println(computeSteadyStateHistogram(Duration.ofSeconds(30), Duration.ofMinutes(120), 10080));
    System.out.println(computeSteadyStateHistogram(Duration.ofMinutes(1), Duration.ofMinutes(120), 10080));
    System.out.println(computeSteadyStateHistogram(Duration.ofMinutes(2), Duration.ofMinutes(120), 10080));
    System.out.println(computeSteadyStateHistogram(Duration.ofMinutes(5), Duration.ofMinutes(120), 10080));
    System.out.println(computeSteadyStateHistogram(Duration.ofMinutes(10), Duration.ofMinutes(120), 10080));
  }

  @Test
  public void distributionConstruction() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final AdaptiveStrategyConfiguration config = new AdaptiveStrategyConfiguration(
        MessageTransport.SMS,
        Set.of("A", "B", "C"),
        Collections.emptyMap(),
        BigDecimal.valueOf(0.05));
    final AdaptiveStrategy strat = new AdaptiveStrategy(List.of(config),
        new FixedCostProvider(List.of(new FixedCostConfiguration(MessageTransport.SMS, Map.of("US", Map.of(
            "A", 10,
            "B", 1,
            "C", 5))))),
        InMemoryVerificationStatsProvider.create(Map.of("US", Map.of(
            "A", new VerificationStats(90.0, 100.0),
            "B", new VerificationStats(8.0, 2.0),
            "C", new VerificationStats(3.0, 7.0)))),
        this.generator);

    final List<Distribution> distributions = strat.buildDistributions(
        MessageTransport.SMS, "US", phoneNumber, Collections.emptySet());
    assertEquals(distributions.size(), 3);
    final Map<String, Distribution> byName = distributions.stream()
        .collect(Collectors.toMap(Distribution::senderName, Function.identity()));
    assertEquals(byName.keySet(), Set.of("A", "B", "C"));
    assertEquals(1.0, byName.get("A").relativeCost(), 0.01);
    assertEquals(.1, byName.get("B").relativeCost(), 0.01);
    assertEquals(.5, byName.get("C").relativeCost(), 0.01);
  }

  @Test
  public void avoidFailedSenders() {
    final Phonenumber.PhoneNumber phoneNumber = PhoneNumberUtil.getInstance().getExampleNumber("US");

    final AdaptiveStrategyConfiguration config = new AdaptiveStrategyConfiguration(
        MessageTransport.SMS,
        Set.of("A", "B", "C"),
        Collections.emptyMap(),
        BigDecimal.valueOf(0.05));
    final AdaptiveStrategy strat = new AdaptiveStrategy(List.of(config),
        new FixedCostProvider(List.of(new FixedCostConfiguration(MessageTransport.SMS, Map.of("US", Map.of(
            "A", 10,
            "B", 1,
            "C", 5))))),
        InMemoryVerificationStatsProvider.create(Map.of("US", Map.of(
            "A", new VerificationStats(90.0, 100.0),
            "B", new VerificationStats(8.0, 2.0),
            "C", new VerificationStats(3.0, 7.0)))),
        this.generator);

    final List<Distribution> aFailed = strat.buildDistributions(MessageTransport.SMS, "US", phoneNumber,
        Set.of("A"));
    final List<Distribution> abFailed = strat.buildDistributions(MessageTransport.SMS, "US", phoneNumber,
        Set.of("A", "B"));
    final List<Distribution> allFailed = strat.buildDistributions(MessageTransport.SMS, "US", phoneNumber,
        Set.of("A", "B", "C"));

    // should be B, C
    assertEquals(aFailed.size(), 2);
    assertFalse(aFailed.stream().map(Distribution::senderName).anyMatch("A"::equals));
    assertEquals(
      aFailed.stream().collect(Collectors.toMap(Distribution::senderName, Distribution::relativeCost)),
      Map.of("B", 0.2, "C", 1.0));

    // should only be C
    assertEquals(abFailed.size(), 1);
    assertEquals(abFailed.get(0).senderName(), "C");
    assertEquals(abFailed.get(0).relativeCost(), 1.0);

    // should use all senders if all senders have had a prior failure
    assertEquals(allFailed.size(), 3);
  }

  @Test
  public void costAdjustment() {
    final JDKRandomGenerator randomGenerator = new JDKRandomGenerator();
    final List<Distribution> distributions = List.of(
        new Distribution("A", 75, 25, 1.0, 0.05),
        new Distribution("B", 70, 30, 0.25, 0.05));
    final Map<String, Long> selections = IntStream.range(0, 10_000)
        .mapToObj(i -> AdaptiveStrategy.sampleChoices(randomGenerator, distributions))
        .collect(Collectors.groupingBy(Distribution::senderName, Collectors.counting()));

    assertTrue(selections.get("B") > selections.get("A"));
  }

  private static Distribution addSuccesses(final Distribution choice, double count) {
    return new Distribution(choice.senderName(), choice.successes() + count, choice.failures(),
        choice.relativeCost(), choice.costCoefficient());
  }

  private static Distribution addFailures(final Distribution choice, double count) {
    return new Distribution(choice.senderName(), choice.successes(), choice.failures() + count,
        choice.relativeCost(), choice.costCoefficient());
  }
}
