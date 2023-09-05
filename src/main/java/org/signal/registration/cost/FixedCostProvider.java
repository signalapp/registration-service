package org.signal.registration.cost;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.util.MapUtil;

/**
 * A {@link CostProvider} that is statically configured at startup
 */
@Singleton
@Requires(missingBeans = BigQueryCostProvider.class)
public class FixedCostProvider implements CostProvider {

  private final Map<MessageTransport, Map<String, Map<String, Integer>>> fixedCosts;


  /**
   * Per-region costs (in micro-dollars, e.g. $0.123456 becomes 123456 and $1 becomes 1,000,000).
   */
  public FixedCostProvider(
      List<FixedCostConfiguration> fixedCostConfigurations) {
    fixedCosts = fixedCostConfigurations.stream().collect(Collectors.toMap(
        FixedCostConfiguration::transport,
        config -> MapUtil.keysToUpperCase(config.regions())));
  }

  @Override
  public Optional<Integer> getCost(final MessageTransport messageTransport, final String region, final String senderName) {
    return Optional.ofNullable(fixedCosts
        .getOrDefault(messageTransport, Collections.emptyMap())
        .getOrDefault(region, Collections.emptyMap())
        .get(senderName));
  }
}
