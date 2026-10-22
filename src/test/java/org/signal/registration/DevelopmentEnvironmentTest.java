/*
 * Copyright 2026 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micronaut.context.DefaultApplicationContextBuilder;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@MicronautTest(environments = Environments.DEVELOPMENT,
    deduceEnvironment = false,
    contextBuilder = DevelopmentEnvironmentTest.EagerInitApplicationContextBuilder.class)
@Property(name = "grpc.server.port", value = "${random.port}")
class DevelopmentEnvironmentTest {

  public static class EagerInitApplicationContextBuilder extends DefaultApplicationContextBuilder {
    public EagerInitApplicationContextBuilder() {
      eagerInitSingletons(true);
    }
  }

  @Inject
  RegistrationService registrationService;

  @Test
  void serviceInit() {
    assertNotNull(registrationService);
  }

}
