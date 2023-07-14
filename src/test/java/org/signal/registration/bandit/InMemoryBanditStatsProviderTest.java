package org.signal.registration.bandit;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.signal.registration.bandit.AdaptiveStrategy.Choice;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InMemoryBanditStatsProviderTest {

  final private List<Choice> choicesA;
  final private List<Choice> choicesB;
  final private List<Choice> choicesC;

  final private InMemoryBanditStatsProvider provider;

  public InMemoryBanditStatsProviderTest() {
    choicesA = List.of(
        new Choice("aaa", 100.0, 0.0),
        new Choice("bbb", 1.0, 0.0)
    );

    choicesB = List.of(
        new Choice("aaa", 10.0, 90.0),
        new Choice("bbb", 0.0, 1.0),
        new Choice("ccc", 50.0, 100.0)
    );

    choicesC = List.of(
        new Choice("ddd", 1.0, 1.0));

    provider = InMemoryBanditStatsProvider.create(
        Map.of("A", choicesA, "B", choicesB, "C", choicesC));
  }

  @Test
  void testGlobalStats() {
    assertEquals(
        new HashSet<>(List.of(
            new Choice("aaa", 110.0, 90.0),
            new Choice("bbb", 1.0, 1.0),
            new Choice("ccc", 50.0, 100.0),
            new Choice("ddd", 1.0, 1.0))),
        new HashSet<>(provider.getGlobalChoices()));
  }

  @Test
  void testRegionalStats() {
    assertEquals(
        new HashSet<>(choicesA),
        new HashSet<>(provider.getRegionalChoices(null, "A")));

    assertEquals(
        new HashSet<>(choicesB),
        new HashSet<>(provider.getRegionalChoices(null, "B")));

    assertEquals(
        new HashSet<>(choicesC),
        new HashSet<>(provider.getRegionalChoices(null, "C")));
  }

  @Test
  void testMissingStats() {
    assertEquals(
        new HashSet<>(List.of()),
        new HashSet<>(provider.getRegionalChoices(null, "D")));
  }
}
