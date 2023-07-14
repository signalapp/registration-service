package org.signal.registration.bandit;

import io.micronaut.context.annotation.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("selection.bandit")
record BanditStatsConfiguration(
    Duration halfLife,
    Duration windowSize) {
}
