package org.signal.registration.bandit;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import javax.validation.constraints.NotNull;
import java.time.Duration;

@ConfigurationProperties("adaptive.bandit")
record VerificationStatsConfiguration(
    @Bindable(defaultValue = "PT1H") @NotNull Duration halfLife,
    @Bindable(defaultValue = "P1D") @NotNull Duration windowSize) {
}
