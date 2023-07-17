package org.signal.registration.bandit;

import com.google.cloud.bigquery.BigQuery;

import com.google.cloud.bigquery.BigQueryOptions;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

@Factory
@Requires(env = Environment.GOOGLE_COMPUTE)
class BigQueryFactory {
  @Singleton
  BigQuery bigQuery() {
    return BigQueryOptions.getDefaultInstance().getService();
  }
}
