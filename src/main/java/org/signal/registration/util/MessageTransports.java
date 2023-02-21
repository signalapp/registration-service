/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.util;

public final class MessageTransports {

  private MessageTransports() {
  }

  public static org.signal.registration.session.MessageTransport getSessionMessageTransportFromSenderTransport(
      final org.signal.registration.sender.MessageTransport senderTransport) {

    return switch (senderTransport) {
      case SMS -> org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_SMS;
      case VOICE -> org.signal.registration.session.MessageTransport.MESSAGE_TRANSPORT_VOICE;
    };
  }

  public static org.signal.registration.sender.MessageTransport getSenderMessageTransportFromSessionTransport(
      final org.signal.registration.session.MessageTransport sessionTransport) {

    return switch (sessionTransport) {
      case MESSAGE_TRANSPORT_SMS -> org.signal.registration.sender.MessageTransport.SMS;
      case MESSAGE_TRANSPORT_VOICE -> org.signal.registration.sender.MessageTransport.VOICE;
      default -> throw new IllegalArgumentException("Unexpected session transport: " + sessionTransport);
    };
  }

  public static org.signal.registration.sender.MessageTransport getSenderMessageTransportFromRpcTransport(
      final org.signal.registration.rpc.MessageTransport rpcTransport) {

    return switch (rpcTransport) {
      case MESSAGE_TRANSPORT_SMS -> org.signal.registration.sender.MessageTransport.SMS;
      case MESSAGE_TRANSPORT_VOICE -> org.signal.registration.sender.MessageTransport.VOICE;
      default -> throw new IllegalArgumentException("Unrecognized RPC transport: " + rpcTransport);
    };
  }
}
