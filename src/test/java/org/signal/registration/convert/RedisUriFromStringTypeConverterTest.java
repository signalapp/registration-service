/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.lettuce.core.RedisURI;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RedisUriFromStringTypeConverterTest {

  private RedisUriFromStringTypeConverter converter;

  @BeforeEach
  void setUp() {
    converter = new RedisUriFromStringTypeConverter();
  }

  @Test
  void convert() {
    final RedisURI expectedURI = RedisURI.builder()
        .withHost("localhost")
        .withPort(7890)
        .withSsl(true)
        .build();

    assertEquals(Optional.of(expectedURI), converter.convert("rediss://localhost:7890", RedisURI.class));

    assertEquals(Optional.empty(), converter.convert("Not a Redis URI", RedisURI.class));
  }
}
