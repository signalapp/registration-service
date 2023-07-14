package org.signal.registration.bandit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.signal.registration.bandit.AdaptiveStrategy.Choice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AdaptiveStrategyTest {

  final static private boolean DETERMINISTIC = true;

  final private Map<String, List<Choice>> stats;
  final private double minimumRegionalCount = 100.0;
  final private Set<String> globalSenders;
  final private Map<String, Set<String>> regionalSenders;
  final private AdaptiveStrategy.Config config;
  final private InMemoryBanditStatsProvider provider;
  final private JDKRandomGenerator generator;
  final private AdaptiveStrategy bandit;

  public AdaptiveStrategyTest() {

    stats = Map.of(
        // simulates a modest amount of registrations
        "R1", List.of(
            new Choice("a", 45.0, 5.0),
            new Choice("b", 20.0, 5.0),
            new Choice("c", 7.5, 15.0)),
        // simulates a cold start
        "R2", List.of(
            new Choice("a", 0.0, 0.0),
            new Choice("b", 0.0, 0.0),
            new Choice("c", 0.0, 0.0)),
        // simulates a country with low registration rates
        "R3", List.of(
            new Choice("a", 0.1, 0.05),
            new Choice("b", 0.2, 0.2),
            new Choice("c", 0.05, 0.1)),
        // derived from RU registration fraud data (24h half-life, 14 day window)
        "R4", List.of(
            new Choice("a", 4201.69687, 424.43773),
            new Choice("b", 3545.6690, 805.458),
            new Choice("c", 0.0, 0.0)));

    globalSenders = Set.of("a", "b", "c");
    regionalSenders = Map.of();
    config = new AdaptiveStrategy.Config(Optional.of(minimumRegionalCount), globalSenders, regionalSenders);
    provider = InMemoryBanditStatsProvider.create(stats);
    generator = new JDKRandomGenerator();

    bandit = new AdaptiveStrategy(
        config,
        provider,
        generator,
        new SimpleMeterRegistry());
  }

  void maybeSeed(final int seed) {
    if (DETERMINISTIC) {
      generator.setSeed(seed);
    }
  }

  Map<String, Integer> computeHistogram(final List<Choice> choices, final int samples) {
    final Map<String, Integer> histogram = new HashMap<>();
    for (int j = 0; j < samples; j++) {
      final String name = bandit.sampleChoices(choices).name();
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
    final List<Choice> choices = stats.get("R1");
    final Map<String, Integer> histogram = computeHistogram(choices, 100);
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
    final List<Choice> choices = stats.get("R2");
    final Map<String, Integer> histogram = computeHistogram(choices, 100);
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
    final List<Choice> choices = stats.get("R3");
    final Map<String, Integer> histogram = computeHistogram(choices, 100);
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
    final List<Choice> choices = stats.get("R4");
    final Map<String, Integer> histogram = computeHistogram(choices, 100);
    int a = histogram.getOrDefault("a", 0);
    int b = histogram.getOrDefault("b", 0);
    int c = histogram.getOrDefault("c", 0);
    assertTrue(74 <= a && a <= 100);
    assertTrue(0 <= b && b <= 1);
    assertTrue(0 <= c && c <= 26);
    assertEquals(100, a + b + c);
  }

  void generateStatsForTrials1M() {
    final List<Choice> choices = stats.get("R1");
    int mina = Integer.MAX_VALUE;
    int maxa = Integer.MIN_VALUE;
    int minb = Integer.MAX_VALUE;
    int maxb = Integer.MIN_VALUE;
    int minc = Integer.MAX_VALUE;
    int maxc = Integer.MIN_VALUE;
    for (int i = 0; i < 1000000; i++) {
      final Map<String, Integer> histogram = computeHistogram(choices, 100);
      int a = histogram.getOrDefault("a", 0);
      mina = Integer.min(mina, a);
      maxa = Integer.max(maxa, a);
      int b = histogram.getOrDefault("b", 0);
      minb = Integer.min(minb, b);
      maxb = Integer.max(maxb, b);
      int c = histogram.getOrDefault("c", 0);
      minc = Integer.min(minc, c);
      maxc = Integer.max(maxc, c);
    }
    System.out.print("a-min=%d a-max=%d\nb-min=%d b-max=%d\nc-min=%d c-max=%d".formatted(mina, maxa, minb, maxb, minc, maxc));
  }

  Map<String, Integer> computeConvergence(
      final Map<String, Double> probabilities,
      final HashMap<String, Choice> choiceMap,
      final int trials
  ) {
    final Map<String, Integer> histogram = new HashMap<>();
    for (int i = 0; i < trials; i++) {
      final List<Choice> choices = choiceMap.values().stream().toList();
      final Choice c = bandit.sampleChoices(choices);
      histogram.put(c.name(), histogram.getOrDefault(c.name(), 0) + 1);

      final double p = probabilities.get(c.name());
      final boolean success = generator.nextDouble() <= p;
      final Choice u = success ? c.addSuccess(1.0) : c.addFailures(1.0);
      choiceMap.put(u.name(), u);
    }
    return histogram;
  }


  @Test
  void testConvergence() {
    maybeSeed(0xabcd1357);
    final Map<String, Double> probabilities = Map.of("a", 0.85, "b", 0.9, "c", 0.75);
    final HashMap<String, Choice> choiceMap = new HashMap<>();
    probabilities.forEach((k, v) -> choiceMap.put(k, Choice.empty(k)));
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
    final HashMap<String, Choice> choiceMap = new HashMap<>();

    int optimalCount = 0;
    int totalCount = 0;
    int shiftOptimalCount = 0;
    int shiftTotalCount = 0;

    for (int i = 0; i < samples; i++) {
      // start with first distribution
      Map<String, Double> probabilities = Map.of("a", 0.85, "b", 0.9, "c", 0.75);
      probabilities.forEach((k, v) -> choiceMap.put(k, Choice.empty(k)));
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
      final List<Choice> choices = names.stream()
          .map(s -> new Choice(s, successRate.getOrDefault(s, 0.0), failureRate.getOrDefault(s, 0.0)))
          .toList();
      final Choice c = bandit.sampleChoices(choices);
      final String name = c.name();

      // update histogram on our choice
      histogram.put(name, histogram.getOrDefault(name, 0) + 1);

      // handle success and failure
      final double p = probabilities.get(c.name());
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

}
