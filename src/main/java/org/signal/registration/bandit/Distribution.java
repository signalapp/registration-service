package org.signal.registration.bandit;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.math3.distribution.BetaDistribution;
import org.apache.commons.math3.random.RandomGenerator;


/**
 * A distribution that models a score or payout for a message sender
 *
 * @param senderName      The name of the sender that this distribution models
 * @param successes       The success parameter (alpha)
 * @param failures        The failure parameter (beta)
 * @param relativeCost    The ratio (0.0, 1.0] between this item's cost and the highest cost alternative. A lower
 *                        relativeCost will cause the distribution to sample higher
 * @param costCoefficient How much verification ratio a 2x cost differential is worth. For example, 0.05 indicates we
 *                        are willing to pay 2x for a provider with a 5% higher verification rate.
 */
public record Distribution(String senderName, double successes, double failures, double relativeCost, double costCoefficient) {

  public static Distribution empty(final String name) {
    return new Distribution(name, 0.0, 0.0, 1.0, 0.0);
  }

  public Distribution(final String senderName, final VerificationStats stats, double relativeCost, double costCoefficient) {
    this(senderName, stats.successes(), stats.failures(), relativeCost, costCoefficient);
  }

  /**
   * Sample this distribution
   *
   * @param generator a source of randomness
   * @return A score from [0.0, 1.0] drawn from this distribution
   */
  public double sample(final RandomGenerator generator) {
    double totalWeight = successes + failures;
    double costAdjustment = costAdjustment();

    // beta distribution requires positive alpha and beta parameters.
    BetaDistribution beta = new BetaDistribution(generator,
        1.0 + Math.min(successes + costAdjustment, totalWeight),
        1.0 + Math.max(failures - costAdjustment, 0.0));
    return beta.sample();
  }

  /**
   * @return how much to add to the success count and subtract from the failure count of the distribution
   */
  @VisibleForTesting
  double costAdjustment() {
    // We are willing to pay 2x for a costCoefficient % improvement in verification ratio.
    // Suppose the coefficient is 0.05, and provider A costs $1 per send and has a history of 75 successes out of 100
    // attempts
    //
    // If provider B has a delivery rate of 70% and B costs $0.5 per send:
    // log2(1.0/.5) * 0.05 => 1.05.
    // Then we would scale the successes to successes 70 + (0.05 * 100) = 75, making A and B equally attractive choices
    //
    // If B costs $0.25 per send:
    // log2(1.0/.25) * 0.05 => 1.10.
    // Then B's successes would be scaled to 80 (higher than A's)
    return (log2(1.0 / relativeCost) * costCoefficient) * (successes + failures);
  }

  private static double LOG_2 = Math.log(2);
  private static double log2(final double x) {
    return Math.log(x) / LOG_2;
  }
}
