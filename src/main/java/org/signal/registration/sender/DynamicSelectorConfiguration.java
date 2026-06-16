package org.signal.registration.sender;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.signal.registration.util.MapUtil;

/**
 * Sender configuration for a single transport {@link DynamicSelector}
 *
 * @param transport       The message transport that this configuration applies to
 * @param fallbackSenders An ordered list of senders to fall back on. The first sender that supports the request will be
 *                        used, or the first sender if no sender supports the request.
 * @param defaultWeights  The proportion of each service type to use
 * @param regionWeights   Override of weights by region
 * @param regionOverrides Map from region to service that should always be used for that region
 * @param unavailableInRegions An optional list of regions where no providers are available for this transport
 */
@EachProperty("selection")
public record DynamicSelectorConfiguration(
    @Parameter MessageTransport transport,
    @NotEmpty List<@NotBlank String> fallbackSenders,
    Map<@NotBlank String, Integer> defaultWeights,
    Map<@NotBlank String, Map<@NotBlank String, Integer>> regionWeights,
    Map<@NotBlank String, @NotBlank String> regionOverrides,
    @Nullable Set<@NotBlank String> unavailableInRegions
) {

  public DynamicSelectorConfiguration {
    regionWeights = MapUtil.keysToUpperCase(regionWeights);
    regionOverrides = MapUtil.keysToUpperCase(regionOverrides);
    if (unavailableInRegions == null) {
      unavailableInRegions = Collections.emptySet();
    }
    unavailableInRegions = unavailableInRegions.stream().map(r -> r.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
  }

}
