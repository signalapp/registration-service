/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.cli;

import org.signal.registration.rpc.SendVerificationCodeRequest;
import org.signal.registration.rpc.SendVerificationCodeResponse;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HexFormat;

@CommandLine.Command(name = "send-verification-code",
    aliases = "send",
    description = "Send a verification code to a phone number")
public class SendVerificationCode implements Runnable {

  @CommandLine.ParentCommand
  private RegistrationClient registrationClient;

  @CommandLine.Parameters(index = "0", description = "Destination phone number (e.g. 18005551234)")
  private long e164;

  @CommandLine.Option(names = {"--transport"},
      description = "Message transport (one of ${COMPLETION-CANDIDATES}; default ${DEFAULT-VALUE})",
      defaultValue = "SMS")
  private MessageTransport messageTransport;

  @CommandLine.Option(names = {"--client-type"},
      description = "Client type (one of ${COMPLETION-CANDIDATES}; default ${DEFAULT-VALUE})",
      defaultValue = "UNKNOWN")
  private ClientType clientType;

  @CommandLine.Option(names = {"--accept-language"},
      description = "Accepted languages (value of an Accept-Language header)")
  private String acceptLanguage;

  @Override
  public void run() {
    try (final CloseableRegistrationServiceGrpcBlockingStubSupplier stubSupplier = registrationClient.getBlockingStubSupplier()) {
      final org.signal.registration.rpc.MessageTransport rpcTransport = switch (messageTransport) {
        case SMS -> org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_SMS;
        case VOICE -> org.signal.registration.rpc.MessageTransport.MESSAGE_TRANSPORT_VOICE;
      };

      final org.signal.registration.rpc.ClientType rpcClientType = switch (clientType) {
        case IOS -> org.signal.registration.rpc.ClientType.CLIENT_TYPE_IOS;
        case ANDROID_WITH_FCM -> org.signal.registration.rpc.ClientType.CLIENT_TYPE_ANDROID_WITH_FCM;
        case ANDROID_WITHOUT_FCM -> org.signal.registration.rpc.ClientType.CLIENT_TYPE_ANDROID_WITHOUT_FCM;
        case UNKNOWN -> org.signal.registration.rpc.ClientType.CLIENT_TYPE_UNSPECIFIED;
      };

      final SendVerificationCodeRequest.Builder requestBuilder = SendVerificationCodeRequest.newBuilder()
          .setE164(e164)
          .setTransport(rpcTransport)
          .setClientType(rpcClientType);

      if (acceptLanguage != null) {
        requestBuilder.setAcceptLanguage(acceptLanguage);
      }

      final SendVerificationCodeResponse response =
          stubSupplier.get().sendVerificationCode(requestBuilder.build());

      final String sessionIdHex = HexFormat.of().formatHex(response.getSessionId().toByteArray());

      System.out.println("Sent code and created registration session " + sessionIdHex);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
