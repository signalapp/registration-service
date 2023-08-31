package org.signal.registration.bandit;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
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
import java.time.temporal.ChronoUnit;
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
  private static final String TRANSPORT_TAG_NAME = "transport";
  private static final String REGION_TAG_NAME = "region";
  private static final String SENDER_TAG_NAME = "sender";

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

  // template for our SQL query
  // the first two %d parameters should be identical (half-life in milliseconds)
  // the third %s parameter should be an ISO-8601 date/time string produced by Instant.toString.
  // the fourth %s parameter should be the message_transport (sms or voice)
  private static final String BASE_SQL_FORMAT = """
      SELECT t.sender_name as sender_name,
        t.region as region,
        sum(t.ok * exp(-log(2) / %d * delta_ms)) as successes,
        sum(1 * exp(-log(2) / %d * delta_ms)) as total
      FROM (
        SELECT sender_name,
          region,
          (cast (verified as integer)) as ok,
          timestamp_diff(current_timestamp(), timestamp, MILLISECOND) as delta_ms
        FROM `registration.analyzed-attempts`
        WHERE timestamp > "%s" AND message_transport = "%s"
      ) as t
      GROUP BY t.sender_name, t.region;
      """;

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
    private final Duration halfLife;
    private final Duration windowSize;
    private final MeterRegistry meterRegistry;
    private final Map<Pair<String, String>, VerificationStats> currentStats;

    volatile private boolean initialized;
    volatile private Map<String, Map<String, VerificationStats>> senderStatsByRegion;

    public Updater(
        final Executor executor,
        final BigQuery bigQuery,
        final MessageTransport messageTransport,
        final Duration halfLife,
        final Duration windowSize,
        final MeterRegistry meterRegistry) {
      this.executor = executor;
      this.bigQuery = bigQuery;
      this.messageTransport = messageTransport;
      this.halfLife = halfLife;
      this.windowSize = windowSize;
      this.meterRegistry = meterRegistry;
      this.currentStats = new HashMap<>();

      this.initialized = false;
      this.senderStatsByRegion = Collections.emptyMap();
    }

    private CompletableFuture<Void> refreshStats() {
      final Instant start = Instant.now().minus(windowSize);
      final String sql = makeSql(halfLife, start);
      return runQuery(sql)
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

    private String makeSql(Duration halfLife, Instant start) {
      final long halfLifeMs = halfLife.toMillis(); // e.g. 604800000
      final String limit = start.truncatedTo(ChronoUnit.SECONDS).toString(); // e.g. "2023-03-09T17:39:54Z"
      return String.format(BASE_SQL_FORMAT, halfLifeMs, halfLifeMs, limit, messageTransportColumnVal(messageTransport));
    }

    private static String messageTransportColumnVal(final MessageTransport messageTransport) {
      return MetricsUtil.getMessageTransportTagValue(
          MessageTransports.getRpcMessageTransportFromSenderTransport(messageTransport));
    }

    private CompletableFuture<Optional<TableResult>> runQuery(final String sql) {
      return CompletableFuture.supplyAsync(() -> {

        final JobId jobId = JobId.of(UUID.randomUUID().toString());
        final QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(sql)
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
          final Tags tags = Tags.of(REGION_TAG_NAME, r, SENDER_TAG_NAME, sender, TRANSPORT_TAG_NAME, messageTransport.name());
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
        mt -> new Updater(executor, bigQuery, mt, config.halfLife(), config.windowSize(), meterRegistry))));
    this.updaterByTransport.forEach((messageTransport, updater) ->
        meterRegistry.gauge(REGION_COUNT_GAUGE_NAME,
            Tags.of(TRANSPORT_TAG_NAME, messageTransport.name()),
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
