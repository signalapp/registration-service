package org.signal.registration.sender;

import static org.signal.registration.sender.DynamicSelector.ADAPTIVE_NAME;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 */
@EachProperty("selection")
public record DynamicSelectorConfiguration(
    @Parameter MessageTransport transport,
    @NotEmpty List<@NotBlank String> fallbackSenders,
    Map<@NotBlank String, Integer> defaultWeights,
    Map<@NotBlank String, Map<@NotBlank String, Integer>> regionWeights,
    Map<@NotBlank String, @NotBlank String> regionOverrides
) {

  public DynamicSelectorConfiguration(
      @Parameter MessageTransport transport,
      @NotEmpty List<@NotBlank String> fallbackSenders,
      Map<@NotBlank String, Integer> defaultWeights,
      Map<@NotBlank String, Map<@NotBlank String, Integer>> regionWeights,
      Map<@NotBlank String, @NotBlank String> regionOverrides) {
    this.transport = transport;
    this.fallbackSenders = fallbackSenders;
    this.defaultWeights = defaultWeights;
    this.regionWeights = MapUtil.keysToUpperCase(regionWeights);
    this.regionOverrides = MapUtil.keysToUpperCase(regionOverrides);
  }


  /**
   * Determine which regions are configured to use adaptive routing.
   */
  public Set<String> computeAdaptiveRegions() {
    final Set<String> adaptiveRegions = new HashSet<>();
    this.regionOverrides().forEach((region, senderName) -> {
      if (senderName.equals(ADAPTIVE_NAME)) {
        adaptiveRegions.add(region);
      }
    });
    this.regionWeights().forEach((region, dist) -> {
      dist.forEach((senderName, weight) -> {
        if (senderName.equals(ADAPTIVE_NAME)) {
          adaptiveRegions.add(region);
        }
      });
    });
    return adaptiveRegions;
  }
}
