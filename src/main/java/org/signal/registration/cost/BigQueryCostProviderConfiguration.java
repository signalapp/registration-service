package org.signal.registration.cost;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

/**
 * @param windowSize How far to look back when computing average costs
 */
@ConfigurationProperties("cost.bigquery")
record BigQueryCostProviderConfiguration(
    @NotBlank String completedAttemptsTableName,
    @NotBlank String analyzedAttemptsTableName,
    @Bindable(defaultValue = "P1D") @NotNull Duration windowSize) {
}
