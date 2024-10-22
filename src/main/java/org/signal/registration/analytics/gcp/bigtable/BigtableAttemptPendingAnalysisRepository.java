/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.gcp.bigtable;

import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.models.Query;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.cloud.bigtable.data.v2.models.RowCell;
import com.google.cloud.bigtable.data.v2.models.RowMutation;
import com.google.cloud.bigtable.data.v2.models.TableId;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.metrics.MetricsUtil;
import org.signal.registration.util.GoogleApiUtil;
import org.signal.registration.util.ReactiveResponseObserver;
import org.signal.registration.util.UUIDUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An "attempt pending analysis" repository that uses <a href="https://cloud.google.com/bigtable">Cloud Bigtable</a> as
 * a backing store.
 */
@Singleton
public class BigtableAttemptPendingAnalysisRepository implements AttemptPendingAnalysisRepository {

  private final BigtableDataClient bigtableDataClient;
  private final Executor executor;
  private final MeterRegistry meterRegistry;

  private final TableId tableId;
  private final String columnFamilyName;

  private static final ByteString DATA_COLUMN_NAME = ByteString.copyFromUtf8("D");

  private static final String STORE_ATTEMPT_COUNTER_NAME =
      MetricsUtil.name(BigtableAttemptPendingAnalysisRepository.class, "store");

  private static final String GET_ATTEMPTS_BY_SENDER_COUNTER_NAME =
      MetricsUtil.name(BigtableAttemptPendingAnalysisRepository.class, "getBySender");

  private static final String REMOVE_ATTEMPT_COUNTER_NAME =
      MetricsUtil.name(BigtableAttemptPendingAnalysisRepository.class, "remove");

  private static final String SENDER_TAG_NAME = "sender";

  private static final Logger logger = LoggerFactory.getLogger(BigtableAttemptPendingAnalysisRepository.class);

  public BigtableAttemptPendingAnalysisRepository(final BigtableDataClient bigtableDataClient,
      @Named(TaskExecutors.IO) final Executor executor,
      final BigtableAttemptPendingAnalysisRepositoryConfiguration configuration,
      final MeterRegistry meterRegistry) {
    this.bigtableDataClient = bigtableDataClient;
    this.executor = executor;

    this.tableId = TableId.of(configuration.tableId());
    this.columnFamilyName = configuration.columnFamilyName();
    this.meterRegistry = meterRegistry;
  }

  @Override
  public CompletableFuture<Void> store(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return GoogleApiUtil.toCompletableFuture(bigtableDataClient.mutateRowAsync(
            RowMutation.create(tableId, getKey(attemptPendingAnalysis))
                .setCell(columnFamilyName, DATA_COLUMN_NAME, attemptPendingAnalysis.toByteString())), executor)
        .whenComplete((ignored, throwable) -> {
          meterRegistry.counter(STORE_ATTEMPT_COUNTER_NAME,
                  SENDER_TAG_NAME, attemptPendingAnalysis.getSenderName())
              .increment();

          if (throwable != null) {
            logger.warn("Failed to store attempt pending analysis", throwable);
          }
        });
  }

  @Override
  public Publisher<AttemptPendingAnalysis> getBySender(final String senderName) {
    return ReactiveResponseObserver.<Row>asFlux(responseObserver ->
            bigtableDataClient.readRowsAsync(Query.create(tableId).prefix(getPrefix(senderName)), responseObserver))
        .doOnNext(row -> meterRegistry.counter(GET_ATTEMPTS_BY_SENDER_COUNTER_NAME, SENDER_TAG_NAME, senderName).increment())
        .doOnError(throwable -> logger.warn("Failed to get attempts pending analysis by sender", throwable))
        .map(this::fromRow);
  }

  @Override
  public CompletableFuture<Void> remove(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return GoogleApiUtil.toCompletableFuture(bigtableDataClient.mutateRowAsync(
            RowMutation.create(tableId, getKey(attemptPendingAnalysis)).deleteRow()), executor)
        .whenComplete((ignored, throwable) -> {
          meterRegistry.counter(REMOVE_ATTEMPT_COUNTER_NAME,
                  SENDER_TAG_NAME, attemptPendingAnalysis.getSenderName())
              .increment();

          if (throwable != null) {
            logger.warn("Failed to remove attempt pending analysis", throwable);
          }
        });
  }

  private AttemptPendingAnalysis fromRow(final Row row) {
    final List<RowCell> cells = row.getCells(columnFamilyName, DATA_COLUMN_NAME);

    if (cells.isEmpty()) {
      throw new IllegalArgumentException("Returned row does not contain a data column");
    } else if (cells.size() > 1) {
      logger.warn("Row contains multiple data cells: {}", row);
    }

    try {
      return AttemptPendingAnalysis.parseFrom(cells.getFirst().getValue());
    } catch (final InvalidProtocolBufferException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ByteString getKey(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return ByteString.copyFromUtf8(attemptPendingAnalysis.getSenderName() +
        "/" + UUIDUtil.uuidFromByteString(attemptPendingAnalysis.getSessionId()) +
        "/" + attemptPendingAnalysis.getAttemptId());
  }

  private static ByteString getPrefix(final String senderName) {
    return ByteString.copyFromUtf8(senderName + "/");
  }
}
