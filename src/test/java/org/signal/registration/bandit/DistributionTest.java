package org.signal.registration.bandit;

import org.apache.commons.math3.random.JDKRandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DistributionTest {

  static Stream<Arguments> costAdjustment() {
    return Stream.of(
        Arguments.of(0.5, 0.05, 5),
        Arguments.of(0.25, 0.05, 10),
        Arguments.of(0.125, 0.05, 15),
        Arguments.of(0.5, 0.2, 20),
        Arguments.of(0.25, 0.2, 40),
        Arguments.of(0.125, 0.2, 60),
        Arguments.of(1.0, 0.05, 0));
  }

  @ParameterizedTest
  @MethodSource
  public void costAdjustment(double relativeCost, double costCoefficient, double expectedAdjustment) {
    double adjustment = new Distribution("test", 70, 30, relativeCost, costCoefficient)
        .costAdjustment();
    assertEquals(expectedAdjustment, adjustment, 0.001);
  }
  @Test
  public void sampleLargeAdjustment() {
    double relativeCost = 1.0 / Math.pow(2.0, 10);
    final Distribution dist = new Distribution("test", 75, 25, relativeCost, 0.1);
    assertEquals(100.0, dist.costAdjustment(), 0.001);

    // Adjusting shouldn't explode (produce negative alpha/beta) if the adjustment is very big
    assertDoesNotThrow(() -> dist.sample(new JDKRandomGenerator()));
  }


}
