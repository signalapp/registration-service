/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.twilio.type.InboundSmsPrice;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@ConfigurationProperties("analytics.twilio.sms.price-estimate")
record TwilioMessagingPriceEstimatorConfiguration(@Bindable(defaultValue = "SHORTCODE,LOCAL,MOBILE") @NotEmpty List<InboundSmsPrice.Type> nanpaNumberTypes,
                                                  @Bindable(defaultValue = "TOLLFREE") @NotEmpty List<InboundSmsPrice.Type> defaultNumberTypes,
                                                  @NotNull Map<String, List<InboundSmsPrice.Type>> regionalNumberTypes) {

  TwilioMessagingPriceEstimatorConfiguration {
    if (regionalNumberTypes == null) {
      regionalNumberTypes = Collections.emptyMap();
    }
  }
}
