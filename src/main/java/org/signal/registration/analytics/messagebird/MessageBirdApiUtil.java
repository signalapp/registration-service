package org.signal.registration.analytics.messagebird;

import com.messagebird.objects.MessageResponse;
import org.apache.commons.lang3.StringUtils;
import org.signal.registration.analytics.AttemptAnalysis;
import org.signal.registration.analytics.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

class MessageBirdApiUtil {

  private MessageBirdApiUtil() {
  }

  static Optional<AttemptAnalysis> extractAttemptAnalysis(final MessageResponse.Recipients recipients) {
    final Optional<Money> maybePrice = recipients.getItems().stream()
        .map(MessageResponse.Items::getPrice)
        .filter(price -> price != null && StringUtils.isNotBlank(price.getCurrency()))
        .map(price -> new Money(new BigDecimal(String.valueOf(price.getAmount())), Currency.getInstance(price.getCurrency().toUpperCase(Locale.ROOT))))
        .reduce(Money::add);

    final OptionalInt maybeMcc = MessageBirdApiUtil.extractFirstInteger(recipients.getItems().stream().map(MessageResponse.Items::getMcc));
    final OptionalInt maybeMnc = MessageBirdApiUtil.extractFirstInteger(recipients.getItems().stream().map(MessageResponse.Items::getMnc));

    return maybePrice.map(price -> new AttemptAnalysis(maybePrice, maybeMcc, maybeMnc));
  }

  static OptionalInt extractFirstInteger(final Stream<String> strings) {
    return strings
        .filter(StringUtils::isNotBlank)
        .map(string -> {
          try {
            return Integer.parseInt(string);
          } catch (final NumberFormatException e) {
            return null;
          }
        })
        .filter(Objects::nonNull)
        .findFirst()
        .map(OptionalInt::of)
        .orElseGet(OptionalInt::empty);
  }
}
