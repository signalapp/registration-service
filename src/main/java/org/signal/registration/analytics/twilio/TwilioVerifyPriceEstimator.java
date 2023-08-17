/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.registration.analytics.twilio;

import com.twilio.type.InboundSmsPrice;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.signal.registration.analytics.AttemptPendingAnalysis;
import org.signal.registration.analytics.Money;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

@Singleton
class TwilioVerifyPriceEstimator extends AbstractTwilioSmsPriceEstimator {

  private final Money verifyFee;

  private static final List<InboundSmsPrice.Type> TYPES_FOR_FIRST_ATTEMPT = List.of(InboundSmsPrice.Type.SHORTCODE, InboundSmsPrice.Type.LOCAL);
  private static final List<InboundSmsPrice.Type> TYPES_FOR_SUBSEQUENT_ATTEMPTS = List.of(InboundSmsPrice.Type.LOCAL);

  TwilioVerifyPriceEstimator(final TwilioSmsPriceProvider dataSource,
      @Value("${analytics.twilio.verify.fee-amount:0.05}") final BigDecimal verifyFeeAmount,
      @Value("${analytics.twilio.verify.fee-currency:USD}") final String verifyFeeCurrency) {

    super(dataSource);

    verifyFee = new Money(verifyFeeAmount, Currency.getInstance(verifyFeeCurrency));
  }

  @Override
  public Optional<Money> estimatePrice(final AttemptPendingAnalysis attemptPendingAnalysis,
      @Nullable final String mcc,
      @Nullable final String mnc) {

    return super.estimatePrice(attemptPendingAnalysis, mcc, mnc)
        .map(price -> {
          if (attemptPendingAnalysis.getVerified()) {
            return price.add(verifyFee);
          }

          return price;
        });
  }

  @Override
  protected List<InboundSmsPrice.Type> getNumberTypes(final AttemptPendingAnalysis attemptPendingAnalysis) {
    return attemptPendingAnalysis.getAttemptId() == 0 ? TYPES_FOR_FIRST_ATTEMPT : TYPES_FOR_SUBSEQUENT_ATTEMPTS;
  }
}
