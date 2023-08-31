package org.signal.registration.cost;


import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.util.MapUtil;
import javax.validation.constraints.NotBlank;
import java.util.Map;

/**
 * An implementation of a {@link CostProvider} that has been statically configured
 *
 * @param transport the transport this cost information is for
 * @param regions   Per-region costs by provider (in micro-dollars, e.g. $0.123456 becomes 123456 and $1 becomes
 *                  1,000,000)
 */
@EachProperty("cost.fixed")
public record FixedCostConfiguration(
    @Parameter MessageTransport transport,
    Map<@NotBlank String, Map<@NotBlank String, Integer>> regions) {

  public FixedCostConfiguration(
      @Parameter MessageTransport transport,
      Map<@NotBlank String, Map<@NotBlank String, Integer>> regions) {
    this.transport = transport;
    this.regions = MapUtil.keysToUpperCase(regions);
  }
}
