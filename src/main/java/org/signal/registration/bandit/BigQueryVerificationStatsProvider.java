package org.signal.registration.bandit;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;
import com.google.i18n.phonenumbers.Phonenumber;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.util.MessageTransports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Requires(bean = BigQuery.class)
public class BigQueryVerificationStatsProvider implements VerificationStatsProvider {

  private static final Logger log = LoggerFactory.getLogger(BigQueryVerificationStatsProvider.class);

  private static final String SUCCESS_GAUGE_NAME = MetricsUtil.name(BigQueryVerificationStatsProvider.class,
      "senderSuccessStats");
  private static final String FAILURE_GAUGE_NAME = MetricsUtil.name(BigQueryVerificationStatsProvider.class,
      "senderFailureStats");
  private static final String REGION_COUNT_GAUGE_NAME = MetricsUtil.name(BigQueryVerificationStatsProvider.class,
      "regionCount");

  private static void logUpdate(final Map<String, Map<String, VerificationStats>> regions, final boolean firstRun) {
    final int numRegions = regions.size();
    final DoubleSummaryStatistics summary = regions.values().stream()
        .flatMap(e -> e.values().stream())
        .mapToDouble(VerificationStats::total).summaryStatistics();
    final String verb = firstRun ? "initialized" : "updated";
    log.debug("{} bandit stats: {} senders across {} regions with total weight {}", verb, summary.getCount(),
        numRegions,
        summary.getSum());
  }

  @Override
  public Optional<VerificationStats> getVerificationStats(final MessageTransport messageTransport,
      final Phonenumber.PhoneNumber phoneNumber,
      final String region,
      final String senderName) {
    return Optional.ofNullable(this.updaterByTransport.get(messageTransport).senderStatsByRegion
        .getOrDefault(region, Collections.emptyMap())
        .get(senderName));
  }

  private static class Updater {

    private final Executor executor;
    private final BigQuery bigQuery;
    private final MessageTransport messageTransport;
    private String tableName;
    private final Duration windowSize;
    private final MeterRegistry meterRegistry;
    private final Map<Pair<String, String>, VerificationStats> currentStats;

    volatile private boolean initialized;
    volatile private Map<String, Map<String, VerificationStats>> senderStatsByRegion;

    public Updater(
        final Executor executor,
        final BigQuery bigQuery,
        final MessageTransport messageTransport,
        final String tableName,
        final Duration windowSize,
        final MeterRegistry meterRegistry) {
      this.executor = executor;
      this.bigQuery = bigQuery;
      this.messageTransport = messageTransport;
      this.tableName = tableName;
      this.windowSize = windowSize;
      this.meterRegistry = meterRegistry;
      this.currentStats = new HashMap<>();

      this.initialized = false;
      this.senderStatsByRegion = Collections.emptyMap();
    }

    private CompletableFuture<Void> refreshStats() {
      return runQuery()
          .thenApply(o -> o.map(this::processTableResult))
          .thenAccept(opt -> {
            if (opt.isPresent()) {
              senderStatsByRegion = opt.get();
              logUpdate(senderStatsByRegion, !initialized);
              initialized = true;
            } else if (!initialized) {
              log.error("failed to initialize bandit stats for {}", this.messageTransport);
            }
          });
    }

    private static String messageTransportColumnVal(final MessageTransport messageTransport) {
      return MetricsUtil.getMessageTransportTagValue(
          MessageTransports.getRpcMessageTransportFromSenderTransport(messageTransport));
    }

    private CompletableFuture<Optional<TableResult>> runQuery() {
      return CompletableFuture.supplyAsync(() -> {
        final QueryParameterValue windowStart = QueryParameterValue.timestamp(
            // Timestamp takes microseconds since epoch
            Instant.now().minus(windowSize).toEpochMilli() * 1000L);

        final QueryParameterValue transport = QueryParameterValue.string(messageTransportColumnVal(messageTransport));
        final JobId jobId = JobId.of(UUID.randomUUID().toString());

        // Fetch the success/failure counts by region for the provided time window. Filter to only the first attempt in
        // each session. Since we use a different selection strategy when making multiple attempts on the same session,
        // bias is introduced in the data. If a provider mostly receives only "second" attempts, (which are likely
        // for numbers that are facing deliverability problems to begin with) their performance might be worse than if
        // they were receiving a random sample of requests.
        final QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder("""
                SELECT
                  sender_name,
                  region,
                  SUM(CAST(verified as integer)) as successes,
                  SUM(1) as total
                FROM `%s`
                WHERE timestamp > @window_start_ts
                AND message_transport = @message_transport
                AND attempt_id = 0
                GROUP BY sender_name, region;
                """.formatted(tableName))
            .addNamedParameter("window_start_ts", windowStart)
            .addNamedParameter("message_transport", transport)
            .setUseLegacySql(false)
            .build();
        final JobInfo jobInfo = JobInfo.newBuilder(queryConfig).setJobId(jobId).build();
        final Job job = bigQuery.create(jobInfo);

        try {
          if (job.waitFor().isDone()) {
            return Optional.of(job.getQueryResults());
          } else {
            log.warn("job did not finish");
            return Optional.empty();
          }
        } catch (InterruptedException e) {
          log.warn("job interrupted: %s", e);
          return Optional.empty();
        }
      }, executor);
    }

    private Map<String, Map<String, VerificationStats>> processTableResult(final TableResult result) {
      final HashMap<String, Map<String, VerificationStats>> regions = new HashMap<>();
      for (FieldValueList row : result.iterateAll()) {
        final String name = row.get("sender_name").getStringValue();
        final String region = row.get("region").getStringValue();
        final double successes = row.get("successes").getDoubleValue();
        final double total = row.get("total").getDoubleValue();
        final double failures = total - successes;
        regions.computeIfAbsent(region, r -> new HashMap<>())
            .put(name, new VerificationStats(successes, failures));
      }

      final ToDoubleFunction<Pair<String, String>> successFn =
          k -> currentStats.getOrDefault(k, VerificationStats.empty()).successes();

      final ToDoubleFunction<Pair<String, String>> failureFn =
          k -> currentStats.getOrDefault(k, VerificationStats.empty()).failures();

      for (String r : regions.keySet()) {
        regions.get(r).forEach((sender, stats) -> {
          final Pair<String, String> key = Pair.of(r, sender);
          final Tags tags = Tags.of(
              MetricsUtil.REGION_CODE_TAG_NAME, r,
              MetricsUtil.SENDER_TAG_NAME, sender,
              MetricsUtil.TRANSPORT_TAG_NAME, messageTransport.name());
          currentStats.put(key, stats);
          meterRegistry.gauge(SUCCESS_GAUGE_NAME, tags, key, successFn);
          meterRegistry.gauge(FAILURE_GAUGE_NAME, tags, key, failureFn);
        });
      }
      return regions;
    }
  }

  private final EnumMap<MessageTransport, Updater> updaterByTransport;

  public BigQueryVerificationStatsProvider(
      @Named(TaskExecutors.IO) final Executor executor,
      final BigQuery bigQuery,
      final VerificationStatsConfiguration config,
      final MeterRegistry meterRegistry) {
    this.updaterByTransport = new EnumMap<>(Arrays.stream(MessageTransport.values()).collect(Collectors.toMap(
        Function.identity(),
        mt -> new Updater(executor, bigQuery, mt, config.tableName(), config.windowSize(), meterRegistry))));
    this.updaterByTransport.forEach((messageTransport, updater) ->
        meterRegistry.gauge(REGION_COUNT_GAUGE_NAME,
            Tags.of(MetricsUtil.TRANSPORT_TAG_NAME, messageTransport.name()),
            updater, u -> u.senderStatsByRegion.size()));
  }

  private CompletableFuture<Void> loadProviderStatistics() {
    return CompletableFuture.allOf(updaterByTransport
        .values().stream()
        .map(Updater::refreshStats)
        .toList()
        .toArray(CompletableFuture[]::new));
  }


  @Scheduled(initialDelay = "5s", fixedDelay = "5m")
  void internalUpdate() {
    loadProviderStatistics().join();
  }
}
