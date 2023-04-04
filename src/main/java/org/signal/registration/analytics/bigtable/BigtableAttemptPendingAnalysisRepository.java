/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.bigtable;

import com.google.api.gax.rpc.ResponseObserver;
import com.google.api.gax.rpc.StreamController;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.models.Query;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.cloud.bigtable.data.v2.models.RowCell;
import com.google.cloud.bigtable.data.v2.models.RowMutation;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.AttemptPendingAnalysisRepository;
import org.signal.registration.util.GoogleApiUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

/**
 * An "attempt pending analysis" repository that uses <a href="https://cloud.google.com/bigtable">Cloud Bigtable</a> as
 * a backing store.
 */
@Singleton
public class BigtableAttemptPendingAnalysisRepository implements AttemptPendingAnalysisRepository {

  private final BigtableDataClient bigtableDataClient;
  private final Executor executor;

  private final String tableId;
  private final String columnFamilyName;

  private static final ByteString DATA_COLUMN_NAME = ByteString.copyFromUtf8("D");

  private static final Logger logger = LoggerFactory.getLogger(BigtableAttemptPendingAnalysisRepository.class);

  private class ReactiveRowResponseObserver implements ResponseObserver<Row> {

    private final FluxSink<AttemptPendingAnalysis> sink;
    private StreamController controller;

    private ReactiveRowResponseObserver(final FluxSink<AttemptPendingAnalysis> sink) {
      this.sink = sink;
    }

    @Override
    public void onStart(final StreamController controller) {
      this.controller = controller;
    }

    @Override
    public void onResponse(final Row row) {
      sink.next(fromRow(row));
    }

    @Override
    public void onError(final Throwable throwable) {
      sink.error(throwable);
    }

    @Override
    public void onComplete() {
      sink.complete();
    }

    public void cancel() {
      if (controller != null) {
        controller.cancel();
      }
    }
  }

  public BigtableAttemptPendingAnalysisRepository(final BigtableDataClient bigtableDataClient,
      @Named(TaskExecutors.IO) final Executor executor,
      final BigtableAttemptPendingAnalysisRepositoryConfiguration configuration) {

    this.bigtableDataClient = bigtableDataClient;
    this.executor = executor;

    this.tableId = configuration.tableId();
    this.columnFamilyName = configuration.columnFamilyName();
  }

  @Override
  public CompletableFuture<Void> store(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return GoogleApiUtil.toCompletableFuture(bigtableDataClient.mutateRowAsync(
        RowMutation.create(tableId, getKey(attemptPendingAnalysis))
            .setCell(columnFamilyName, DATA_COLUMN_NAME, attemptPendingAnalysis.toByteString())), executor);
  }

  @Override
  public CompletableFuture<Optional<AttemptPendingAnalysis>> getByRemoteIdentifier(final String senderName, final String remoteId) {
    return GoogleApiUtil.toCompletableFuture(bigtableDataClient.readRowAsync(tableId, getKey(senderName, remoteId)), executor)
        .thenApply(maybeRow -> Optional.ofNullable(maybeRow)
            .flatMap(row -> row.getCells(columnFamilyName, DATA_COLUMN_NAME)
                .stream()
                .findFirst()
                .map(dataCell -> {
                  try {
                    return AttemptPendingAnalysis.parseFrom(dataCell.getValue());
                  } catch (final InvalidProtocolBufferException e) {
                    throw new UncheckedIOException(e);
                  }
                })));
  }

  @Override
  public Publisher<AttemptPendingAnalysis> getBySender(final String senderName) {
    return Flux.create(sink -> {
      final ReactiveRowResponseObserver responseObserver = new ReactiveRowResponseObserver(sink);
      bigtableDataClient.readRowsAsync(Query.create(tableId).prefix(getPrefix(senderName)), responseObserver);

      sink.onDispose(responseObserver::cancel);
    });
  }

  @Override
  public CompletableFuture<Void> remove(final String senderName, final String remoteId) {
    return GoogleApiUtil.toCompletableFuture(bigtableDataClient.mutateRowAsync(RowMutation.create(tableId, getKey(senderName, remoteId)).deleteRow()), executor);
  }

  private AttemptPendingAnalysis fromRow(final Row row) {
    final List<RowCell> cells = row.getCells(columnFamilyName, DATA_COLUMN_NAME);

    if (cells.isEmpty()) {
      throw new IllegalArgumentException("Returned row does not contain a data column");
    } else if (cells.size() > 1) {
      logger.warn("Row contains multiple data cells: {}", row);
    }

    try {
      return AttemptPendingAnalysis.parseFrom(cells.get(0).getValue());
    } catch (final InvalidProtocolBufferException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ByteString getKey(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return getKey(attemptPendingAnalysis.getSenderName(), attemptPendingAnalysis.getRemoteId());
  }

  private static ByteString getKey(final String senderName, final String remoteId) {
    return ByteString.copyFromUtf8(senderName + "/" + remoteId);
  }

  private static ByteString getPrefix(final String senderName) {
    return ByteString.copyFromUtf8(senderName + "/");
  }
}
