package org.signal.registration.bandit;

import com.google.i18n.phonenumbers.Phonenumber;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provide a method for getting the current statistics on verification code success and failure rates.
 * <p>
 * The interface assumes that the statistics are tracked locally (and periodically updated asynchronously).
 * It also includes code to compute global statistics by aggregating regional data.
 */
public interface BanditStatsProvider {

  /**
   * Get bandit statistics for the given region.
   * <p>
   * Currently, this only uses region, but in the future we may also use MCC/MNC based on phone number.
   * @param phoneNumber
   * @param region
   * @return
   */
  List<AdaptiveStrategy.Choice> getRegionalChoices(
      Phonenumber.PhoneNumber phoneNumber,
      String region);

  /**
   * Get the default list of choices for the bandit to use. This list may be overridden on a per-region basis.
   *
   * @return default list of choices for the bandit to select from.
   */
  List<AdaptiveStrategy.Choice> getGlobalChoices();

  static List<AdaptiveStrategy.Choice> computeGlobalChoices(Map<String, List<AdaptiveStrategy.Choice>> choicesByRegion) {
    final Map<String, AdaptiveStrategy.Choice> methods = new HashMap<>();
    choicesByRegion.values().forEach((choices) -> {
      choices.forEach(choice -> {
        methods.merge(choice.name(), choice, (prev, curr) -> new AdaptiveStrategy.Choice(
            choice.name(),
            prev.successes() + curr.successes(),
            prev.failures() + curr.failures())
        );
      });
    });

    return new ArrayList<>(methods.values());
  }
}
