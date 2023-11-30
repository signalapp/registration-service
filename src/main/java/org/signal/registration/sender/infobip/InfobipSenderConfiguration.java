/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.sender.infobip;

import com.google.i18n.phonenumbers.Phonenumber;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import org.signal.registration.sender.SenderIdSelectorConfiguration;
import org.signal.registration.util.PhoneNumbers;
import javax.validation.constraints.NotBlank;
import java.util.Map;
import java.util.stream.Collectors;

@Context
@ConfigurationProperties("infobip")
public record InfobipSenderConfiguration (
    @NotBlank String defaultSenderId,
    Map<@NotBlank String, @NotBlank String> senderIdsByRegion) implements SenderIdSelectorConfiguration
{}
