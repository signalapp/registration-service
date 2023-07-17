package org.signal.registration.bandit;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import javax.validation.constraints.NotNull;
import java.time.Duration;

@ConfigurationProperties("selection.bandit")
record BanditStatsConfiguration(
    @Bindable(defaultValue = "P1D") @NotNull Duration halfLife,
    @Bindable(defaultValue = "P10D") @NotNull Duration windowSize) {
}
