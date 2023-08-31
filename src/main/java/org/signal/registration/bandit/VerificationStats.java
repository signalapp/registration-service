package org.signal.registration.bandit;

public record VerificationStats(double successes, double failures) {
  private static final VerificationStats EMPTY = new VerificationStats(0.0, 0.0);

  public static VerificationStats empty() {
    return EMPTY;
  }
  public double total() {
    return successes + failures;
  }
}
