/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.cli.bigtable;

import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient;
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminSettings;
import com.google.cloud.bigtable.admin.v2.models.CreateTableRequest;
import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.BigtableDataSettings;
import com.google.cloud.bigtable.emulator.v2.Emulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.cli.KeyAndPrice;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BigtableInfobipDefaultSmsPricesRepositoryTest {
  private Emulator emulator;
  private BigtableDataClient bigtableDataClient;

  private BigtableInfobipDefaultSmsPricesRepository repository;

  private static final String PROJECT_ID = "test";
  private static final String INSTANCE_ID = "test";

  private static final String TABLE_ID = "infobip-default-sms-prices";
  private static final String COLUMN_FAMILY_NAME = "D";

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

    repository = new BigtableInfobipDefaultSmsPricesRepository(bigtableDataClient,
        new BigtableInfobipDefaultSmsPricesRepositoryConfiguration(TABLE_ID, COLUMN_FAMILY_NAME));
  }

  @AfterEach
  void tearDown() {
    bigtableDataClient.close();
    emulator.stop();
  }

  @Test
  void storeAndGetDefaultPrice() {
    final String testKey = "AB";
    final Stream<KeyAndPrice> prices = Stream.of(new KeyAndPrice("AB", new BigDecimal("0.166")),
        new KeyAndPrice("AD", new BigDecimal("0.055")),
        new KeyAndPrice("DE", new BigDecimal("0.012")));

    assertEquals(Optional.empty(), repository.get(testKey));

    repository.storeDefaultPrices(prices);

    assertEquals(Optional.of(new BigDecimal("0.166")), repository.get(testKey));
  }
}
