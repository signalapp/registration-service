package org.signal.registration.bandit;

import com.google.i18n.phonenumbers.Phonenumber;
import org.signal.registration.sender.MessageTransport;
import java.util.Optional;

/**
 * Retrieve current statistics on verification code success and failure rates.
 * <p>
 * The interface assumes that the statistics are tracked locally (and periodically updated asynchronously).
 * It also includes code to compute global statistics by aggregating regional data.
 */
public interface VerificationStatsProvider {

  /**
   * Get verification success/failure rate statistics
   *
   * @param messageTransport The messageTransport to get stats for
   * @param phoneNumber      The phone number the stats should apply to
   * @param region           The two character region code of the number
   * @param senderName       The message provider to get stats for
   *
   * @return Two doubles representing the success and failure count for the sender, if stats are available
   */
  Optional<VerificationStats> getVerificationStats(
      MessageTransport messageTransport,
      Phonenumber.PhoneNumber phoneNumber,
      String region,
      String senderName);
}
