package org.signal.registration.bandit;

import com.google.cloud.bigquery.BigQuery;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;

@ConfigurationProperties("stats.bigquery")
@Requires(bean = BigQuery.class)
record VerificationStatsConfiguration(
    @Bindable(defaultValue = "P1D") @NotNull Duration windowSize,
    @NotNull @NotEmpty String tableName) {
}
