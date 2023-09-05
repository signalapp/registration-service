package org.signal.registration.sender;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.commons.math3.random.AbstractRandomGenerator;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.apache.commons.math3.random.RandomGenerator;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class DynamicSelectorTest {

  private static final VerificationCodeSender SENDER_FALLBACK = buildTestSender("default", true);
  private static final VerificationCodeSender SENDER_A = buildTestSender("A", true);
  private static final VerificationCodeSender SENDER_B = buildTestSender("B", true);
  private static final VerificationCodeSender UNSUPPORTED = buildTestSender("unsupported", false);

  private static final List<VerificationCodeSender> SENDERS = List.of(SENDER_A, SENDER_B, UNSUPPORTED, SENDER_FALLBACK);

  static Stream<Arguments> select() {
    final Stream<Arguments> args = Stream.of(
        // defaultWeights, regionWeights, PN region, rng value, expected sender

        // default
        Arguments.of(Map.of(UNSUPPORTED.getName(), 1), Collections.emptyMap(), "US", 1.0, SENDER_FALLBACK),
        // default weights
        Arguments.of(Map.of(SENDER_A.getName(), 1), Collections.emptyMap(), "US", 1.0, SENDER_A),
        // region weights
        Arguments.of(Map.of(SENDER_A.getName(), 1), Map.of("US", Map.of(SENDER_B.getName(), 1)), "US", 1.0, SENDER_B),
        // 0 should go to first service
        Arguments.of(Map.of(SENDER_A.getName(), 4, SENDER_B.getName(), 6), Collections.emptyMap(), "US", 0.0, SENDER_A),
        // <.4 should go to first service
        Arguments.of(Map.of(SENDER_A.getName(), 4, SENDER_B.getName(), 6), Collections.emptyMap(), "US", 0.3999,
            SENDER_A),
        // > .4 should go to second service
        Arguments.of(Map.of(SENDER_A.getName(), 4, SENDER_B.getName(), 6), Collections.emptyMap(), "US", 0.40001,
            SENDER_B),
        // 1.0 should go to second service
        Arguments.of(Map.of(SENDER_A.getName(), 4, SENDER_B.getName(), 6), Collections.emptyMap(), "US", 1.0, SENDER_B)
    );

    return args.flatMap(arg -> Stream.of(MessageTransport.VOICE, MessageTransport.SMS)
        .map(transport ->
            Arguments.of(Stream.concat(
                Stream.of(transport),
                Arrays.stream(arg.get())).toArray())));
  }


  @ParameterizedTest
  @MethodSource
  void select(
      final MessageTransport transport,
      Map<String, Integer> defaults,
      Map<String, Map<String, Integer>> overrides,
      final String region,
      double randomValue,
      VerificationCodeSender expected) {

    // sort by name for deterministic order
    TreeMap<String, Integer> sortedDefaults = new TreeMap<>(defaults);
    Map<String, Map<String, Integer>> sortedOverrides = overrides
        .entrySet()
        .stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            e -> new TreeMap<>(e.getValue())
        ));

    RandomGenerator rg = new AbstractRandomGenerator() {
      @Override
      public void setSeed(final long seed) {
      }

      @Override
      public double nextDouble() {
        return randomValue;
      }
    };

    final DynamicSelectorConfiguration config = new DynamicSelectorConfiguration(
        transport,
        List.of(SENDER_FALLBACK.getName()),
        sortedDefaults,
        sortedOverrides,
        Collections.emptyMap());

    final DynamicSelector ts = new DynamicSelector(
        rg,
        new SimpleMeterRegistry(),
        config,
        null,
        List.of(SENDER_A, SENDER_B, UNSUPPORTED, SENDER_FALLBACK));

    final VerificationCodeSender actual = ts.chooseVerificationCodeSender(
        PhoneNumberUtil.getInstance().getExampleNumber(region),
        Collections.emptyList(),
        ClientType.IOS,
        null);

    assertEquals(actual, expected);
  }

  static Stream<Arguments> override() {
    final Phonenumber.PhoneNumber number = PhoneNumberUtil.getInstance().getExampleNumber("US");
    final Phonenumber.PhoneNumber mxNumber = PhoneNumberUtil.getInstance().getExampleNumber("MX");

    return Stream.of(
        // no override
        Arguments.of(Map.of(), number, SENDER_FALLBACK),
        // override by region
        Arguments.of(Map.of("mx", SENDER_A.getName()), mxNumber, SENDER_A)
    );
  }

  @ParameterizedTest
  @MethodSource
  void override(
      Map<String, String> regionOverrides,
      final Phonenumber.PhoneNumber number,
      VerificationCodeSender expected) {

    final DynamicSelectorConfiguration config = new DynamicSelectorConfiguration(
        MessageTransport.SMS,
        List.of(SENDER_FALLBACK.getName()),
        Map.of(),
        Map.of(),
        regionOverrides);

    final DynamicSelector ts = new DynamicSelector(new SimpleMeterRegistry(), config, null, SENDERS);
    final VerificationCodeSender actual = ts.chooseVerificationCodeSender(
        number,
        Collections.emptyList(),
        ClientType.IOS,
        null);
    assertEquals(expected, actual);
  }


  static Stream<Arguments> ranking() {
    return Stream.of(
        Arguments.of(null, List.of(UNSUPPORTED), UNSUPPORTED),
        Arguments.of(null, List.of(UNSUPPORTED, SENDER_A), SENDER_A),
        Arguments.of(null, List.of(SENDER_A, UNSUPPORTED), SENDER_A),
        Arguments.of(UNSUPPORTED, List.of(UNSUPPORTED, SENDER_A), SENDER_A),
        Arguments.of(SENDER_A, List.of(SENDER_B), SENDER_A),
        Arguments.of(UNSUPPORTED, List.of(SENDER_A, SENDER_B), SENDER_A)
    );
  }


  @ParameterizedTest
  @MethodSource
  public void ranking(
      final @Nullable VerificationCodeSender choice,
      final List<VerificationCodeSender> fallbacks,
      final VerificationCodeSender expected) {
    final DynamicSelectorConfiguration config = new DynamicSelectorConfiguration(
        MessageTransport.SMS,
        fallbacks.stream().map(VerificationCodeSender::getName).toList(),
        choice == null ? Map.of() : Map.of(choice.getName(), 1),
        Map.of(),
        Map.of());

    final DynamicSelector ts = new DynamicSelector(new SimpleMeterRegistry(), config, null, SENDERS);
    final Phonenumber.PhoneNumber num = PhoneNumberUtil.getInstance().getExampleNumber("US");
    final VerificationCodeSender actual = ts.chooseVerificationCodeSender(num, Collections.emptyList(),
        ClientType.IOS, null);
    assertEquals(actual, expected);
  }

  @Test
  public void preferredSender() {
    final DynamicSelectorConfiguration config = new DynamicSelectorConfiguration(
        MessageTransport.SMS,
        List.of(SENDER_FALLBACK.getName()),
        Map.of(),
        Map.of(),
        Map.of());

    final DynamicSelector ts = new DynamicSelector(new SimpleMeterRegistry(), config, null, List.of(SENDER_FALLBACK, SENDER_A));
    final VerificationCodeSender actual = ts.chooseVerificationCodeSender(
        PhoneNumberUtil.getInstance().getExampleNumber("US"),
        Collections.emptyList(),
        ClientType.IOS,
        SENDER_A.getName());
    assertEquals(SENDER_A, actual);
  }



  private static VerificationCodeSender buildTestSender(final String name, final boolean supports) {
    return new VerificationCodeSender() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public Duration getAttemptTtl() {
        return Duration.ofMinutes(10);
      }

      @Override
      public boolean supportsTransport(final MessageTransport transport) {
        return supports;
      }

      @Override
      public boolean supportsLanguageAndClient(
          final MessageTransport messageTransport,
          final Phonenumber.PhoneNumber phoneNumber,
          final List<Locale.LanguageRange> languageRanges,
          final ClientType clientType
      ) {
        return supports;
      }

      @Override
      public CompletableFuture<AttemptData> sendVerificationCode(
          final MessageTransport messageTransport,
          final Phonenumber.PhoneNumber phoneNumber,
          final List<Locale.LanguageRange> languageRanges,
          final ClientType clientType
      ) throws UnsupportedMessageTransportException {
        return null;
      }

      @Override
      public CompletableFuture<Boolean> checkVerificationCode(
          final String verificationCode, final byte[] senderData
      ) {
        return null;
      }
    };
  }
}
