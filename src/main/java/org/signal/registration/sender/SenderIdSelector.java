package org.signal.registration.sender;

import com.google.i18n.phonenumbers.Phonenumber;
import org.signal.registration.util.PhoneNumbers;
import java.util.Map;
import java.util.stream.Collectors;

public class SenderIdSelector {
  // Map from region to senderId to use for that region
  private final Map<String, String> senderIdsByRegion;
  private final String defaultSenderId;

  public SenderIdSelector(final Map<String, String> senderIdsByRegion, final String defaultSenderId) {
    this.senderIdsByRegion = senderIdsByRegion
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            e -> e.getKey().toUpperCase(),
            Map.Entry::getValue
        ));
    this.defaultSenderId = defaultSenderId;
  }


  /**
   * Get a senderId to use
   *
   * @param phoneNumber target phone number to send to
   * @return the sender for a message to phoneNumber (e.g. an alphanumeric sender id, a phone number)
   */
  public String getSenderId(final Phonenumber.PhoneNumber phoneNumber) {
    final String region = PhoneNumbers.regionCodeUpper(phoneNumber);
    return senderIdsByRegion.getOrDefault(region, defaultSenderId);
  }

}
