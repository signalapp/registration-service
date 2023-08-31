package org.signal.registration.bandit;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import java.util.Map;
import java.util.Set;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.util.MapUtil;

/**
 * Configuration parameters for an {@link AdaptiveStrategy}
 *
 * These parameters configure the set of senders that should be considered when doing Adaptive Routing. The
 * corresponding AdaptiveStrategy will take statistical information about these sender's success rates and costs
 * and sample among these senders.
 *
 * @param transport             The message transport this configuration is for (e.x. sms or voice)
 * @param defaultChoices        The default set of senders to consider when using a bandit
 * @param regionalChoices       The set of senders to use for specific regions (overrides defaultChoices)
 */
@EachProperty("adaptive")
public record AdaptiveStrategyConfiguration(
    @Parameter MessageTransport transport,
    @NotEmpty Set<@NotBlank String> defaultChoices,
    Map<@NotBlank String, Set<@NotBlank String>> regionalChoices) {

  public AdaptiveStrategyConfiguration(
      @Parameter MessageTransport transport,
      @NotEmpty Set<@NotBlank String> defaultChoices,
      Map<@NotBlank String, Set<@NotBlank String>> regionalChoices) {
    this.transport = transport;
    this.defaultChoices = defaultChoices;
    this.regionalChoices = MapUtil.keysToUpperCase(regionalChoices);
  }

}
