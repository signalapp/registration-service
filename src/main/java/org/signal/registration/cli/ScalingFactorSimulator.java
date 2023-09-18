/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.cli;

import java.util.List;
import java.util.stream.IntStream;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.signal.registration.bandit.AdaptiveStrategy;
import org.signal.registration.bandit.Distribution;
import picocli.CommandLine;

/**
 * Build a matrix comparing success rates with scaling factors.
 * <p>
 * The idea is that for a given choice A with a base rate and a scaling factor, we
 * can compute the rate for another scaled choice (B) where the adaptive router
 * splits evenly between A/B. With a scaling factor of 1 we expect the split to
 * occur when A and B have the same rate. With a scaling factor of 2, we expect
 * that A/B and will be evenly split when B's rate is lower. For example, we
 * have empirically found that if A's rate is 80% and we scale by 2, the 50/50
 * split occurs when B's rate is 66%.
 */
@CommandLine.Command(name = "scaling-factor-matrix",
    description = "Build a matrix comparing success rates with scaling factors")
class ScalingFactorSimulator implements Runnable {

  @Override
  public void run() {
    System.out.print("    ");

    // scale factors [1.0, 1.1, ... 3.0]
    final double[] scaleFactors = IntStream.range(10, 30 + 1)
        .mapToDouble(i -> i / 10.0)
        .toArray();

    for (double scaleFactor : scaleFactors) {
      System.out.print("%4.1f ".formatted(scaleFactor));
    }
    System.out.println();
    for (int rate = 10; rate < 100; rate += 5) {
      System.out.print("%3d  ".formatted(rate));
      for (double scaleFactor : scaleFactors) {
        int r2 = rate;
        while (r2 > 0) {
          double z = computeFraction(rate, r2, scaleFactor);
          if (z > 0.5) {
            break;
          }
          r2 -= 1;
        }
        System.out.print("%3d  ".formatted(r2));
      }
      System.out.println();
    }
  }

  /**
   * Compute the fraction of traffic that went to choice A (out of the total).
   *
   * @param sa           The success rate of A
   * @param sb           The success rate of B
   * @param scaleFactor  The scaling factor: the amount to scale up B successes
   * @return The fraction of traffic that went to A (between 0 and 1).
   */
  private double computeFraction(final double sa, final double sb, final double scaleFactor) {
    final int samples = 10000;
    final double fa = 100.0 - sa;
    final double fb = 100.0 - sb;
    final List<Distribution> choices = List.of(
        new Distribution("a", sa, fa, 1.0, 0.05),
        new Distribution("b", sb, fb, 1 / scaleFactor, 0.05));
    int ca = 0;
    for (int i = 0; i < samples; i++) {
      final Distribution c = AdaptiveStrategy.sampleChoices(new JDKRandomGenerator(), choices);
      if (c.senderName().equals("a")) {
        ca += 1;
      }
    }
    return (double) ca / samples;
  }

  public static void main(final String... args) {
    new CommandLine(new ScalingFactorSimulator()).execute(args);
  }
}
