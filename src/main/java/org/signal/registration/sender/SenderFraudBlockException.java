package org.signal.registration.sender;

public class SenderFraudBlockException extends SenderRejectedRequestException {

  public SenderFraudBlockException(final String message) {
    super(message);
  }

  public SenderFraudBlockException(final Throwable cause) {
    super(cause);
  }
}
