/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.twilio.type.InboundSmsPrice;
import jakarta.inject.Singleton;
import java.util.List;
import org.signal.registration.analytics.AttemptPendingAnalysis;

@Singleton
class TwilioMessagingPriceEstimator extends AbstractTwilioSmsPriceEstimator {

  private final TwilioMessagingPriceEstimatorConfiguration configuration;

  TwilioMessagingPriceEstimator(final TwilioSmsPriceProvider dataSource,
      final TwilioMessagingPriceEstimatorConfiguration configuration) {

    super(dataSource);
    this.configuration = configuration;
  }

  @Override
  protected List<InboundSmsPrice.Type> getNumberTypes(final AttemptPendingAnalysis attemptPendingAnalysis) {
    final List<InboundSmsPrice.Type> numberTypes;

    if (PhoneNumberUtil.getInstance().isNANPACountry(attemptPendingAnalysis.getRegion())) {
      numberTypes = configuration.nanpaNumberTypes();
    } else if (configuration.regionalNumberTypes().containsKey(attemptPendingAnalysis.getRegion())) {
      numberTypes = configuration.regionalNumberTypes().get(attemptPendingAnalysis.getRegion());
    } else {
      numberTypes = configuration.defaultNumberTypes();
    }

    return numberTypes;
  }
}
