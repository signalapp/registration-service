package org.signal.registration.bandit;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import com.google.i18n.phonenumbers.Phonenumber;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.signal.registration.bandit.AdaptiveStrategy.Choice;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.sender.DynamicSelectorConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@Requires(bean = BigQuery.class)
public class BigQueryBanditStatsProvider implements BanditStatsProvider {

  private static final Logger log = LoggerFactory.getLogger(BigQueryBanditStatsProvider.class);

  private static final String SUCCESS_GAUGE_NAME = MetricsUtil.name(AdaptiveStrategy.class,"senderSuccessStats");
  private static final String FAILURE_GAUGE_NAME = MetricsUtil.name(AdaptiveStrategy.class,"senderFailureStats");
  private static final String REGION_TAG_NAME = "region";
  private static final String SENDER_TAG_NAME = "sender";

  private static void logUpdate(final Map<String, List<Choice>> regions, final List<Choice> totals, final boolean firstRun) {
    final int numSenders = totals.size();
    final int numRegions = regions.size();
    final double totalWeight = totals.stream().map(Choice::total).reduce(0.0, Double::sum);
    final String verb = firstRun ? "initialized" : "updated";
    log.info("{} bandit stats: {} senders across {} regions with total weight {}", verb, numSenders, numRegions, totalWeight);
    if (totalWeight == 0.0) {
      log.warn("bandit stats had zero weight across all regions -- is bigquery not returning data?");
    }
  }

  private static CompletableFuture<Void> loadProviderStatistics(final BigQueryBanditStatsProvider provider) {
    return provider.updater.computeRegionalChoices().thenApply(opt -> {
      if (opt.isPresent()) {
        final Map<String, List<Choice>> regions = opt.get();
        final List<Choice> totals = BanditStatsProvider.computeGlobalChoices(regions);
        provider.regionalChoices = regions;
        provider.globalChoices = totals;
        logUpdate(regions, totals, !provider.initialized);
        provider.initialized = true;
      } else if (!provider.initialized) {
        log.error("failed to initialize bandit stats");
      }
      return null;
    });
  }

  // template for our SQL query
  // the first two %d parameters should be identical (half-life in milliseconds)
  // the last %s parameter should be an ISO-8601 date/time string produced by Instant.toString.
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
  WHERE timestamp > "%s"
) as t
GROUP BY t.sender_name, t.region;
""";

  private static class Updater {

    private final Executor executor;
    private final BigQuery bigQuery;
    private final Duration halfLife;
    private final Duration windowSize;
    private final MeterRegistry meterRegistry;

    public Updater(
        final Executor executor,
        final BigQuery bigQuery,
        final Duration halfLife,
        final Duration windowSize,
        final MeterRegistry meterRegistry
    ) {
      this.executor = executor;
      this.bigQuery = bigQuery;
      this.halfLife = halfLife;
      this.windowSize = windowSize;
      this.meterRegistry = meterRegistry;
    }

    private CompletableFuture<Optional<Map<String, List<Choice>>>> computeRegionalChoices() {
      final Instant start = Instant.now().minus(windowSize);
      final String sql = makeSql(halfLife, start);
      return runQuery(sql).thenApply(o -> o.map(this::processTableResult));
    }

    private String makeSql(Duration halfLife, Instant start) {
      final long halfLifeMs = halfLife.toMillis(); // e.g. 604800000
      final String limit = start.truncatedTo(ChronoUnit.SECONDS).toString(); // e.g. "2023-03-09T17:39:54Z"
      return String.format(BASE_SQL_FORMAT, halfLifeMs, halfLifeMs, limit);
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

    private Map<String, List<Choice>> processTableResult(final TableResult result) {
      final HashMap<String, List<Choice>> regions = new HashMap<>();
      for (FieldValueList row : result.iterateAll()) {
        final String name = row.get("sender_name").getStringValue();
        final String region = row.get("region").getStringValue();
        final double successes = row.get("successes").getDoubleValue();
        final double total = row.get("total").getDoubleValue();
        final double failures = total - successes;
        final Choice choice = new Choice(name, successes, failures);
        regions.computeIfAbsent(region, r -> new ArrayList<>()).add(choice);
      }
      for (String r : regions.keySet()) {
        for (Choice c : regions.get(r)) {
          final Tags tags = Tags.of(REGION_TAG_NAME, r, SENDER_TAG_NAME, c.name());
          meterRegistry.gauge(SUCCESS_GAUGE_NAME, tags, c.successes());
          meterRegistry.gauge(FAILURE_GAUGE_NAME, tags, c.failures());
        }
      }
      return regions;
    }
  }

  private final Updater updater;

  volatile private boolean initialized;
  volatile private Map<String, List<Choice>> regionalChoices;
  volatile private List<Choice> globalChoices;

  public BigQueryBanditStatsProvider(
      @Named(TaskExecutors.IO) final Executor executor,
      final BigQuery bigQuery,
      final BanditStatsConfiguration config,
      final MeterRegistry meterRegistry) {
    this.initialized = false;
    this.updater = new Updater(executor, bigQuery, config.halfLife(), config.windowSize(), meterRegistry);
    this.regionalChoices = null;
    this.globalChoices = null;
  }

  @Override
  public List<Choice> getRegionalChoices(
      final Phonenumber.PhoneNumber phoneNumber,
      final String region) {
    return regionalChoices.getOrDefault(region, List.of());
  }

  @Override
  public List<Choice> getGlobalChoices() {
    return globalChoices == null ? List.of() : globalChoices;
  }

  @Scheduled(initialDelay = "5s", fixedDelay = "5m")
  CompletableFuture<Void> internalUpdate() {
    return loadProviderStatistics(this);
  }
}
