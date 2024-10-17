/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.cli.bigtable;

import com.google.cloud.bigtable.data.v2.BigtableDataClient;
import com.google.cloud.bigtable.data.v2.models.BulkMutation;
import com.google.cloud.bigtable.data.v2.models.Mutation;
import com.google.cloud.bigtable.data.v2.models.Row;
import com.google.cloud.bigtable.data.v2.models.TableId;
import jakarta.inject.Singleton;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.stream.Stream;
import org.signal.registration.cli.KeyAndPrice;

@Singleton
public class BigtableInfobipDefaultSmsPricesRepository {
  private final BigtableDataClient bigtableDataClient;

  private final TableId tableId;
  private final String columnFamilyName;

  private static final String PRICE_COLUMN_NAME = "P";

  public BigtableInfobipDefaultSmsPricesRepository(
      final BigtableDataClient bigtableDataClient,
      final BigtableInfobipDefaultSmsPricesRepositoryConfiguration configuration) {
    this.bigtableDataClient = bigtableDataClient;
    this.tableId = TableId.of(configuration.tableId());
    this.columnFamilyName = configuration.columnFamilyName();
  }

  public void storeDefaultPrices(final Stream<KeyAndPrice> defaultPrices) {
    final BulkMutation bulkMutation = BulkMutation.create(tableId);

    defaultPrices.forEach(price -> bulkMutation.add(price.key(),
              Mutation.create().setCell(columnFamilyName, PRICE_COLUMN_NAME, price.price().toString())));

    bigtableDataClient.bulkMutateRows(bulkMutation);
  }

  /**
   * @param key MCC/MNC or region code
   * @return the default price associated with the given key, or an empty Optional if none exist.
   */
  public Optional<BigDecimal> get(final String key) {
    final Row row = bigtableDataClient.readRow(tableId, key);
    if (row != null) {
      return row.getCells(columnFamilyName, PRICE_COLUMN_NAME)
          .stream()
          .findFirst()
          .map(price -> new BigDecimal(price.getValue().toStringUtf8()));
    }
    return Optional.empty();
  }
}
