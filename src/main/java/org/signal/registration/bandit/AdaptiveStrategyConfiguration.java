/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.bandit;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.util.MapUtil;

/**
 * Configuration parameters for an {@link AdaptiveStrategy}
 *
 * These parameters configure the set of senders that should be considered when doing Adaptive Routing. The
 * corresponding AdaptiveStrategy will take statistical information about these sender's success rates and costs
 * and sample among these senders.
 *
 * @param transport       The message transport this configuration is for (e.x. sms or voice)
 * @param defaultChoices  The default set of senders to consider when using a bandit
 * @param regionalChoices The set of senders to use for specific regions (overrides defaultChoices)
 * @param costCoefficient How much verification ratio a 2x cost differential is worth. For example, 0.05 indicates we
 *                        are willing to pay 2x for a provider with a 5% higher verification rate.
 */
@EachProperty("adaptive")
public record AdaptiveStrategyConfiguration(
    @Parameter MessageTransport transport,
    @NotEmpty Set<@NotBlank String> defaultChoices,
    Map<@NotBlank String, Set<@NotBlank String>> regionalChoices,
    @Nullable BigDecimal costCoefficient) {

  public AdaptiveStrategyConfiguration(
      @Parameter MessageTransport transport,
      @NotEmpty Set<@NotBlank String> defaultChoices,
      Map<@NotBlank String, Set<@NotBlank String>> regionalChoices,
      @Bindable(defaultValue = "0.05") BigDecimal costCoefficient) {
    this.transport = transport;
    this.defaultChoices = defaultChoices;
    this.regionalChoices = MapUtil.keysToUpperCase(regionalChoices);
    this.costCoefficient = costCoefficient;
  }

}
