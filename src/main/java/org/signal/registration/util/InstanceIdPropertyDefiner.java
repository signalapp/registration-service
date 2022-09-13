/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.util;

import ch.qos.logback.core.PropertyDefinerBase;
import com.google.cloud.MetadataConfig;
import org.apache.commons.lang3.StringUtils;
import java.util.UUID;

/**
 * An instance ID property definer attempts to provide a GCP instance ID as a value, but falls back to a random ID if
 * no instance ID is available.
 *
 * @see <a href="https://logback.qos.ch/manual/configuration.html#definingPropsOnTheFly">Logback Manual - Chapter 3:
 * Logback configuration - Defining variables, aka properties, on the fly</a>
 */
@SuppressWarnings("unused")
public class InstanceIdPropertyDefiner extends PropertyDefinerBase {

  private static final String FALLBACK_INSTANCE_ID = UUID.randomUUID().toString();

  @Override
  public String getPropertyValue() {
    return StringUtils.defaultIfBlank(MetadataConfig.getInstanceId(), FALLBACK_INSTANCE_ID);
  }
}
