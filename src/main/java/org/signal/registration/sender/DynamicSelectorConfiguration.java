package org.signal.registration.sender;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;

/**
 * Sender configuration for a single transport {@link DynamicSelector}
 *
 * @param transport       The message transport that this configuration applies to
 * @param fallbackSenders An ordered list of senders to fall back on. The first sender that supports the request will be
 *                        used, or the first sender if no sender supports the request.
 * @param defaultWeights  The proportion of each service type to use
 * @param regionWeights   Override of weights by region
 * @param regionOverrides Map from region to service that should always be used for that region
 * @param minimumRegionalAdaptiveWeight The minimum weight required to use a bandit's regional weights. Below this weight the global bandit weights will be used.
 * @param defaultAdaptiveChoices The default list of senders to consider when using a bandit
 * @param regionalAdaptiveChoices The lists of senders to use for specific regions (overrides defaultAdaptiveChoices)
 */
@EachProperty("selection")
public record DynamicSelectorConfiguration(
    @Parameter MessageTransport transport,
    @NotEmpty List<@NotBlank String> fallbackSenders,
    Map<@NotBlank String, Integer> defaultWeights,
    Map<@NotBlank String, Map<@NotBlank String, Integer>> regionWeights,
    Map<@NotBlank String, @NotBlank String> regionOverrides,
    Optional<Integer> minimumRegionalAdaptiveWeight,
    @NotEmpty List<@NotBlank String> defaultAdaptiveChoices,
    Map<@NotBlank String, List<@NotBlank String>> regionalAdaptiveChoices
) {
}
