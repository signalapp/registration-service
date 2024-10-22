/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.gcp.bigtable;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminSettings;
import com.google.cloud.bigtable.admin.v2.models.CreateTableRequest;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.BigtableDataSettings;
import com.google.cloud.bigtable.emulator.v2.Emulator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.rpc.ClientType;
import org.signal.registration.rpc.MessageTransport;
import org.signal.registration.util.UUIDUtil;
import reactor.core.publisher.Flux;

class BigtableAttemptPendingAnalysisRepositoryTest {

  private Emulator emulator;
  private BigtableDataClient bigtableDataClient;
  private ExecutorService executorService;

  private BigtableAttemptPendingAnalysisRepository repository;

  private static final String PROJECT_ID = "test";
  private static final String INSTANCE_ID = "test";

  private static final String TABLE_ID = "attempts-pending-analysis";
  private static final String COLUMN_FAMILY_NAME = "A";

  private static final AtomicInteger ATTEMPT_ID_COUNTER = new AtomicInteger();

  @BeforeEach
  void setUp() throws IOException, InterruptedException, TimeoutException {
    emulator = Emulator.createBundled();
    emulator.start();

    try (final BigtableTableAdminClient tableAdminClient =
        BigtableTableAdminClient.create(BigtableTableAdminSettings.newBuilderForEmulator(emulator.getPort())
            .setProjectId(PROJECT_ID)
            .setInstanceId(INSTANCE_ID)
            .build())) {

      tableAdminClient.createTable(CreateTableRequest.of(TABLE_ID).addFamily(COLUMN_FAMILY_NAME));
    }

    bigtableDataClient = BigtableDataClient.create(BigtableDataSettings.newBuilderForEmulator(emulator.getPort())
        .setProjectId(PROJECT_ID)
        .setInstanceId(INSTANCE_ID)
        .build());

    executorService = Executors.newSingleThreadExecutor();

    repository = new BigtableAttemptPendingAnalysisRepository(bigtableDataClient,
        executorService,
        new BigtableAttemptPendingAnalysisRepositoryConfiguration(TABLE_ID, COLUMN_FAMILY_NAME), new SimpleMeterRegistry());
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    bigtableDataClient.close();
    emulator.stop();

    executorService.shutdown();

    //noinspection ResultOfMethodCallIgnored
    executorService.awaitTermination(1, TimeUnit.SECONDS);
  }

  @Test
  void storeAndGetBySender() {
    final String senderName = "test";
    final AttemptPendingAnalysis attemptPendingAnalysis = buildAttemptPendingAnalysis(senderName);

    assertEquals(Collections.emptyList(), Flux.from(repository.getBySender(senderName)).collectList().block());

    repository.store(attemptPendingAnalysis).join();

    assertEquals(List.of(attemptPendingAnalysis), Flux.from(repository.getBySender(senderName)).collectList().block());
  }

  @Test
  void storeDuplicateEvent() {
    final String senderName = "test";
    final AttemptPendingAnalysis attemptPendingAnalysis = buildAttemptPendingAnalysis(senderName);

    assertDoesNotThrow(() -> repository.store(attemptPendingAnalysis).join());
    assertDoesNotThrow(() -> repository.store(attemptPendingAnalysis).join());

    assertEquals(List.of(attemptPendingAnalysis), Flux.from(repository.getBySender(senderName)).collectList().block());
  }

  @Test
  void getBySender() {
    final String sender = "first";
    final Set<AttemptPendingAnalysis> expectedAttemptsPendingAnalysis = new HashSet<>();

    for (int i = 0; i < 10; i++) {
      final AttemptPendingAnalysis attemptPendingAnalysis = buildAttemptPendingAnalysis(sender);

      repository.store(attemptPendingAnalysis).join();
      expectedAttemptsPendingAnalysis.add(attemptPendingAnalysis);
    }

    for (int i = 0; i < 10; i++) {
      repository.store(buildAttemptPendingAnalysis(sender + "-unexpected")).join();
    }

    assertEquals(expectedAttemptsPendingAnalysis,
        Flux.from(repository.getBySender(sender)).collect(Collectors.toSet()).block());
  }

  @Test
  void remove() {
    final String senderName = "test";
    final AttemptPendingAnalysis removedAttempt = buildAttemptPendingAnalysis(senderName);
    final AttemptPendingAnalysis remainingAttempt = buildAttemptPendingAnalysis(senderName);

    assertDoesNotThrow(() -> repository.remove(remainingAttempt).join());

    repository.store(removedAttempt).join();
    repository.store(remainingAttempt).join();

    assertDoesNotThrow(() -> repository.remove(removedAttempt).join());

    assertEquals(List.of(remainingAttempt), Flux.from(repository.getBySender(senderName)).collectList().block());
  }

  private static AttemptPendingAnalysis buildAttemptPendingAnalysis(final String senderName) {
    return AttemptPendingAnalysis.newBuilder()
        .setSessionId(UUIDUtil.uuidToByteString(UUID.randomUUID()))
        .setAttemptId(ATTEMPT_ID_COUNTER.incrementAndGet())
        .setSenderName(senderName)
        .setRemoteId(RandomStringUtils.randomAlphanumeric(16))
        .setMessageTransport(ThreadLocalRandom.current().nextBoolean() ? MessageTransport.MESSAGE_TRANSPORT_SMS : MessageTransport.MESSAGE_TRANSPORT_VOICE)
        .setClientType(ClientType.CLIENT_TYPE_UNSPECIFIED)
        .setRegion(RandomStringUtils.randomAlphabetic(2))
        .setTimestampEpochMillis(System.currentTimeMillis())
        .setAccountExistsWithE164(ThreadLocalRandom.current().nextBoolean())
        .setVerified(ThreadLocalRandom.current().nextBoolean())
        .build();
  }
}
