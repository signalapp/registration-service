package org.signal.registration.sender;

import java.util.Optional;

public class SenderInvalidParametersException extends SenderRejectedRequestException {
  public enum ParamName {
    NUMBER,
    LOCALE;
  }

  private Optional<ParamName> paramName;

  public SenderInvalidParametersException(final Throwable throwable, final Optional<ParamName> invalidParamName) {
    super(throwable);
    this.paramName = invalidParamName;
  }

  public Optional<ParamName> getParamName() {
    return paramName;
  }
}
