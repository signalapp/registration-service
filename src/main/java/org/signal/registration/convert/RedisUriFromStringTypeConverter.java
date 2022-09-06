/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.convert;

import io.lettuce.core.RedisURI;
import io.micronaut.core.convert.ConversionContext;
import io.micronaut.core.convert.TypeConverter;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Optional;

@Singleton
public class RedisUriFromStringTypeConverter implements TypeConverter<String, RedisURI> {

  private static final Logger logger = LoggerFactory.getLogger(RedisUriFromStringTypeConverter.class);

  @Override
  public Optional<RedisURI> convert(final String string,
      final Class<RedisURI> targetType,
      final ConversionContext context) {

    try {
      return Optional.of(RedisURI.create(string));
    } catch (final Exception e) {
      logger.warn("Failed to parse string as Redis URI: {}", string, e);
      return Optional.empty();
    }
  }
}
