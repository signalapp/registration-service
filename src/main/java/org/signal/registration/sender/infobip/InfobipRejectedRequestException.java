/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.infobip;

import com.infobip.model.MessageStatus;
import org.signal.registration.sender.SenderRejectedRequestException;

public class InfobipRejectedRequestException extends SenderRejectedRequestException {
  final MessageStatus status;
  public InfobipRejectedRequestException(final MessageStatus status) {
    super(String.format("Failed to deliver message. Status: %s", status));
    this.status = status;
  }

  String getStatusCode() {
    return status.getId().toString();
  }
}
