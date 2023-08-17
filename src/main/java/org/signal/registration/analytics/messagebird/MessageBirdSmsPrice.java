/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.messagebird;

import org.signal.registration.analytics.Money;
import javax.annotation.Nullable;

/**
 * A price to send an SMS message to a destination network via MessageBird. Prices from MessageBird may represent a
 * price to send a message to a specific network (in which case {@link #region()}, {@link #mcc()}, and {@link #mnc()}
 * will all be non-null), a default price for a region (in which case {@link #mnc()} will be null), or a global default
 * price (in which case {@link #region()}, {@link #mcc()}, and {@link #mnc()} will all be null). MessageBird applies
 * pricing rules in order of decreasing specificity.
 *
 * @param region the ISO 3166-1 alpha-2 region code for the destination; may be {@code null} if this price represents
 *               the global default price
 * @param mcc the mcc for the destination; may be {@code null} if this price represents the global default price
 * @param mnc the mnc for the destination; may be {@code null} if this price represents the global default price or a
 *            regional default price
 * @param price the price to send an SMS message to the given destination
 *
 * @see <a href="https://developers.messagebird.com/quickstarts/pricingapi/list-outbound-sms-prices/">Pricing API</a>
 */
record MessageBirdSmsPrice(@Nullable String region, @Nullable String mcc, @Nullable String mnc, Money price) {
}
