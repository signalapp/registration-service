package org.signal.registration.bandit;

import org.apache.commons.math3.distribution.BetaDistribution;
import org.apache.commons.math3.random.RandomGenerator;


/**
 * A distribution that models a score or payout for a message sender
 *
 * @param senderName   The name of the sender that this distribution models
 * @param successes    The success parameter (alpha)
 * @param failures     The failure parameter (beta)
 * @param relativeCost The ratio (0.0, 1.0] between this item's cost and the highest cost alternative. A lower
 *                     relativeCost will cause the distribution to sample higher
 */
public record Distribution(String senderName, double successes, double failures, double relativeCost) {

  public static Distribution empty(final String name) {
    return new Distribution(name, 0.0, 0.0, 1.0);
  }

  public Distribution(String senderName, VerificationStats verificationStats, double relativeCost) {
    this(senderName, verificationStats.successes(), verificationStats.failures(), relativeCost);
  }

  /**
   * Sample this distribution
   *
   * @param generator a source of randomness
   * @return A score from [0.0, 1.0] drawn from this distribution
   */
  public double sample(final RandomGenerator generator) {
    double scaledSuccess = successes / relativeCost;

    // beta distribution requires positive alpha and beta parameters.
    BetaDistribution beta = new BetaDistribution(generator, scaledSuccess + 1.0, failures + 1.0);
    return beta.sample();
  }
}
