package org.signal.registration.cost;

import java.util.Optional;
import org.signal.registration.sender.MessageTransport;

/**
 * Provides estimates for the cost to send messages
 */
public interface CostProvider {

  /**
   * Estimate the cost to send a messages.
   *
   * @param messageTransport The type of messages to retrieve cost information for
   * @param region The region the message will be sent to
   * @param senderName The provider the message will be sent with
   *
   * @return an estimate of the cost to send a message in micro-dollars
   */
  Optional<Integer> getCost(final MessageTransport messageTransport, final String region, final String senderName);
}
