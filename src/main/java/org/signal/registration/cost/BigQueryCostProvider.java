package org.signal.registration.cost;

import static org.signal.registration.metrics.MetricsUtil.messageTransportFromTagValue;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.util.MessageTransports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Requires(bean = BigQuery.class)
public class BigQueryCostProvider implements CostProvider {

  private final Logger logger = LoggerFactory.getLogger(getClass());

  private static final String COST_GAUGE_NAME = MetricsUtil.name(BigQueryCostProvider.class, "costMicros");

  private final Duration windowSize;
  private final BigQuery bigQuery;
  private final MeterRegistry meterRegistry;
  private final String analyzedAttemptsTableName;
  private final String completedAttemptsTableName;

  private volatile Map<MessageTransport, Map<String, Map<String, Integer>>> absoluteCosts = Collections.emptyMap();

  public BigQueryCostProvider(
      final BigQueryCostProviderConfiguration costProviderConfiguration,
      final BigQuery bigQuery,
      final MeterRegistry meterRegistry) {
    this.bigQuery = bigQuery;
    this.meterRegistry = meterRegistry;
    this.windowSize = costProviderConfiguration.windowSize();
    this.analyzedAttemptsTableName = costProviderConfiguration.analyzedAttemptsTableName();
    this.completedAttemptsTableName = costProviderConfiguration.completedAttemptsTableName();
  }

  @Override
  public Optional<Integer> getCost(final MessageTransport messageTransport, final String region,
      final String senderName) {
    return Optional.ofNullable(this.absoluteCosts
        .getOrDefault(messageTransport, Collections.emptyMap())
        .getOrDefault(region.toUpperCase(), Collections.emptyMap())
        .get(senderName));
  }

  @Scheduled(initialDelay = "5s", fixedDelay = "5m")
  void internalUpdate() {
    final QueryParameterValue windowStart = QueryParameterValue.timestamp(
        // Timestamp takes microseconds since epoch
        Instant.now().minus(windowSize).toEpochMilli() * 1000L);

    final JobId jobId = JobId.of(UUID.randomUUID().toString());
    final QueryJobConfiguration queryConfig = QueryJobConfiguration
        .newBuilder("""
            WITH prices AS (
              SELECT
                completed.timestamp, completed.region, completed.sender_name, completed.message_transport,
                COALESCE(price_micros, estimated_price_micros) as price_micros,
                COALESCE(currency, estimated_price_currency) as currency
              FROM `%s` as analyzed
              JOIN `%s` as completed
                ON analyzed.session_id=completed.session_id
                AND analyzed.attempt_id=completed.attempt_id
                AND analyzed.attempt_id=0
                AND completed.timestamp > @window_start_ts
                AND analyzed.timestamp > @window_start_ts
                AND currency is NOT NULL)
            SELECT
              message_transport, sender_name, region, currency, AVG(price_micros) as avg_price
            FROM prices
            GROUP BY message_transport, region, sender_name, currency
            ORDER BY region, sender_name
            """.formatted(analyzedAttemptsTableName, completedAttemptsTableName))
        .setUseLegacySql(false)
        .addNamedParameter("window_start_ts", windowStart)
        .build();
    final JobInfo jobInfo = JobInfo.newBuilder(queryConfig).setJobId(jobId).build();
    final Job job = bigQuery.create(jobInfo);

    try {
      if (job.waitFor().isDone()) {
        processQueryResult(job.getQueryResults());
      } else {
        logger.warn("job did not finish");
      }
    } catch (InterruptedException e) {
      logger.warn("job interrupted: %s", e);
    } catch (Exception e) {
      logger.error("failed to process result set", e);
      throw e;
    }
  }

  private void processQueryResult(final TableResult tableResult) {

    // The raw costs (micro-dollars) before normalization. {transport: {region: {sender: cost}}}
    final HashMap<MessageTransport, Map<String, Map<String, Integer>>> costs = new HashMap<>();
    for (FieldValueList row : tableResult.iterateAll()) {
      final String senderName = row.get("sender_name").getStringValue();
      final String region = row.get("region").getStringValue();
      final MessageTransport transport = parseMessageTransport(row.get("message_transport").getStringValue());

      final String currency = row.get("currency").getStringValue();
      if (!currency.equals("USD")) {
        logger.warn("unexpected currency for sender={}, region={}, transport={}: {}",
            senderName, region, transport, currency);
        continue;
      }

      final int avgCostMicros = (int) row.get("avg_price").getDoubleValue();

      costs
          .computeIfAbsent(transport, k -> new HashMap<>())
          .computeIfAbsent(region.toUpperCase(), k -> new HashMap<>())
          .put(senderName, avgCostMicros);
    }

    this.absoluteCosts = costs;

    costs.forEach((transport, regionalCosts) ->
        regionalCosts.forEach((region, senderCosts) ->
            senderCosts.forEach((sender, cost) ->
                meterRegistry.gauge(
                    COST_GAUGE_NAME, Tags.of(
                        MetricsUtil.TRANSPORT_TAG_NAME, transport.name(),
                        MetricsUtil.REGION_CODE_TAG_NAME, region,
                        MetricsUtil.SENDER_TAG_NAME, sender),
                    this, t -> t.absoluteCosts
                        .getOrDefault(transport, Collections.emptyMap())
                        .getOrDefault(region, Collections.emptyMap())
                        .getOrDefault(sender, 0)))));

  }

  private static MessageTransport parseMessageTransport(final String messageTransport) {
    return MessageTransports.getSenderMessageTransportFromRpcTransport(messageTransportFromTagValue(messageTransport));
  }
}
