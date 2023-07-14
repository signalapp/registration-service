package org.signal.registration.sender;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.apache.commons.math3.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.signal.registration.bandit.BanditStatsProvider;
import org.signal.registration.bandit.InMemoryBanditStatsProvider;
import org.signal.registration.bandit.AdaptiveStrategy;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicSelectorBanditTest {

  private static String MV = "messagebird-verify";
  private static String TV = "twilio-verify";
  private static String TPM = "twilio-programmable-messaging";
  private static String A = "adaptive";

  private DynamicSelector selector;

  public DynamicSelectorBanditTest() {

    final RandomGenerator random = new JDKRandomGenerator();

    final MessageTransport transport = MessageTransport.SMS;
    final List<String> fallbackSenders = List.of(TPM);
    final Map<String, Integer> defaultWeights = Map.of(TV, 50, MV, 50);
    final Map<String, Map<String, Integer>> regionWeights = Map.of(
        "DE", Map.of(TV, 0, MV, 0, A, 100),
        "ZZ", Map.of(TV, 10, MV, 10, A, 80));
    final Map<String, String> regionOverrides = Map.of();
    final Optional<Integer> minimumRegionalBanditCount = Optional.of(10);
    final List<String> defaultBanditChoices = List.of(TV, MV);
    final Map<String, List<String>> regionalBanditChoices = Map.of();

    final DynamicSelectorConfiguration config = new DynamicSelectorConfiguration(
        transport, fallbackSenders, defaultWeights, regionWeights, regionOverrides, minimumRegionalBanditCount, defaultBanditChoices, regionalBanditChoices
    );

    final List<VerificationCodeSender> verificationCodeSenders =
        List.of(
            new TestVerificationCodeSender(TV, List.of("de", "en")),
            new TestVerificationCodeSender(TPM, List.of("de", "en", "fr", "zh")),
            new TestVerificationCodeSender(MV, List.of("de", "en", "fr")));
    final BanditStatsProvider banditStatsProvider = InMemoryBanditStatsProvider.create(
        Map.of(
            "DE", List.of(
              new AdaptiveStrategy.Choice(TV, 100.0, 10.0),
              new AdaptiveStrategy.Choice(MV, 95.0, 15.0)),
            "ZZ", List.of(
                new AdaptiveStrategy.Choice(TV, 70.0, 40.0),
                new AdaptiveStrategy.Choice(MV, 85.0, 25.0)
            )));

    this.selector = new DynamicSelector(random, config, verificationCodeSenders, banditStatsProvider, new SimpleMeterRegistry());
  }

  public String chooseName(final String phoneNumber, final String region, final String language) {
    try {
      return selector.chooseVerificationCodeSenderName(
          PhoneNumberUtil.getInstance().parse(phoneNumber, region),
          List.of(new Locale.LanguageRange(language)),
          ClientType.IOS,
          null);
    } catch (NumberParseException e) {
      throw new RuntimeException(e);
    }
  }

  public Map<String, Integer> chooseNameHistogram(final int count, final String phoneNumber, final String region, final String language) {
    final HashMap<String, Integer> histogram = new HashMap<>();
    for(int i = 0; i < count; i++) {
      final String name = chooseName(phoneNumber, region, language);
      histogram.put(name, histogram.getOrDefault(name, 0) + 1);
    }
    return histogram;
  }

  // in germany with german language, we will mostly rely on our bandit which has better stats for twilio
  @Test
  void testDeDe() {
    final Map<String, Integer> h = chooseNameHistogram(1000, "+492115684962", "de", "de");
    assertTrue(h.getOrDefault(TV, 0) > 700);
    assertEquals(1000, h.getOrDefault(TV, 0) + h.getOrDefault(MV, 0));
  }

  // in germany with french language, our bandit will try to use twilio-verify but fall back to twilio-programmable-messaging
  // because twilio-verify doesn't support french. thus we expect to see a lower percentage of messagebird-verify
  // and expect the rest to be twilio-programmable-messaging.
  @Test
  void testDeFr() {
    final Map<String, Integer> h = chooseNameHistogram(1000, "+492115684962", "de", "fr");
    assertTrue(h.getOrDefault(MV, 0) > 100);
    assertEquals(0, h.getOrDefault(TV, 0));
    assertEquals(1000, h.getOrDefault(TPM, 0) + h.getOrDefault(MV, 0));
  }

  // in the US with english, we will just use a static 50/50% split with no fallback happening.
  @Test
  void testUsEn() {
    final Map<String, Integer> h = chooseNameHistogram(1000, "+12155551111", "us", "en");
    assertTrue(h.getOrDefault(TV, 0) > 400);
    assertTrue(h.getOrDefault(MV, 0) > 400);
    assertEquals(1000, h.getOrDefault(TV, 0) + h.getOrDefault(MV, 0));
  }

}
