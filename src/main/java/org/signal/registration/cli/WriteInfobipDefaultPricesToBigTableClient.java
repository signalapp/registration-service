/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.cli;

import io.micronaut.configuration.picocli.PicocliRunner;
import jakarta.inject.Inject;
import org.signal.registration.cli.bigtable.BigtableInfobipDefaultSmsPricesRepository;
import picocli.CommandLine;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVFormat;

/**
 * Parse a CSV File of Infobip SMS default prices and write them to Bigtable.
 * The CLI assumes that the CSV file format is
 * <a href="https://commons.apache.org/proper/commons-csv/apidocs/org/apache/commons/csv/CSVFormat.html#EXCEL">CSVFormat.EXCEL</a>
 * with headers.
 */
@CommandLine.Command(name = "write-infobip-default-prices-to-bigtable",
    description = "Parses a CSV file of Infobip SMS default prices and writes them to Bigtable")
public class WriteInfobipDefaultPricesToBigTableClient implements Runnable {

  @CommandLine.Option(names = {"--csv-file"},
    description = "Path to CSV file containing default prices")
  private File csvFile;

  @CommandLine.Option(names = {"--key-column-name"},
      description = "Column name to use as the key value")
  private String keyColumnName;

  @CommandLine.Option(names = {"--price-column-name"},
      defaultValue = "Price",
      description = "Column name to use for prices")
  private String priceColumnName;

  private final BigtableInfobipDefaultSmsPricesRepository repository;

  @Inject
  public WriteInfobipDefaultPricesToBigTableClient(final BigtableInfobipDefaultSmsPricesRepository repository) {
    this.repository = repository;
  }

  @Override
  public void run() {
    try {
      final Stream<KeyAndPrice> keyAndPrices = CSVFormat.EXCEL.builder()
          .setHeader()
          .setSkipHeaderRecord(true)
          .build()
          .parse(new FileReader(csvFile))
          .stream()
          .filter(record -> record.get(keyColumnName) != null && record.get(priceColumnName) != null)
          .map(record -> new KeyAndPrice(record.get(keyColumnName), new BigDecimal(record.get(priceColumnName))));

      repository.storeDefaultPrices(keyAndPrices);

      System.out.printf("Successfully wrote prices for file %s to Bigtable\n", csvFile.getName());
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static void main(final String... args) {
    PicocliRunner.run(WriteInfobipDefaultPricesToBigTableClient.class, args);
  }
}
