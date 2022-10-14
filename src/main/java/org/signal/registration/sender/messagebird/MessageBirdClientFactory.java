/*
 * Copyright 2022 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.signal.registration.sender.messagebird;

import com.messagebird.MessageBirdClient;
import com.messagebird.MessageBirdServiceImpl;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

@Factory
public class MessageBirdClientFactory {
  @Singleton
  MessageBirdClient messageBirdClient(final MessageBirdConfiguration configuration) {
    return new MessageBirdClient(new MessageBirdServiceImpl(configuration.accessKey()));
  }
}
