/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.twilio.type.InboundSmsPrice;
import org.signal.registration.analytics.Money;

import java.util.EnumMap;

/**
 * A price to send an SMS message to a specific region or network via Twilio.
 *
 * @param region the ISO 3166-1 alpha-2 region code for the destination
 * @param mcc the MCC of the destination
 * @param mnc the MNC of the destination
 * @param pricesByNumberType a map of prices by originating phone number type
 */
record TwilioSmsPrice(String region, String mcc, String mnc, EnumMap<InboundSmsPrice.Type, Money> pricesByNumberType) {
}
