package org.signal.registration.util;

import jakarta.inject.Singleton;
import org.apache.commons.math3.random.AbstractRandomGenerator;
import java.util.concurrent.ThreadLocalRandom;

@Singleton
public class ThreadLocalRandomGenerator extends AbstractRandomGenerator {
  @Override
  public void setSeed(final long seed) {
    ThreadLocalRandom.current().setSeed(seed);
  }

  @Override
  public double nextDouble() {
    return ThreadLocalRandom.current().nextDouble();
  }
}
