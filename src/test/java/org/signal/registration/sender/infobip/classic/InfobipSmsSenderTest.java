package org.signal.registration.sender.infobip.classic;

import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.infobip.ApiException;
import com.infobip.api.SmsApi;
import com.infobip.model.SmsResponse;
import com.infobip.model.SmsResponseDetails;
import com.infobip.model.MessageStatus;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.signal.registration.sender.ApiClientInstrumenter;
import org.signal.registration.sender.ClientType;
import org.signal.registration.sender.MessageTransport;
import org.signal.registration.sender.SenderFraudBlockException;
import org.signal.registration.sender.VerificationCodeGenerator;
import org.signal.registration.sender.VerificationSmsBodyProvider;
import org.signal.registration.sender.infobip.InfobipSenderConfiguration;

public class InfobipSmsSenderTest {
  private VerificationCodeGenerator codeGenerator;
  private SmsApi client;
  private InfobipSmsSender sender;

  @BeforeEach
  public void setup() {
    final InfobipSmsConfiguration config = new InfobipSmsConfiguration(Duration.ofSeconds(1));
    codeGenerator = mock(VerificationCodeGenerator.class);
    final VerificationSmsBodyProvider bodyProvider = mock(VerificationSmsBodyProvider.class);
    client = mock(SmsApi.class);

    when(bodyProvider.getVerificationBody(any(), any(), any(), any())).thenReturn("body");

    sender = new InfobipSmsSender(Runnable::run, config, codeGenerator, bodyProvider, client, mock(
        ApiClientInstrumenter.class), new InfobipSenderConfiguration("test", Collections.emptyMap()));
  }

  @Test
  void sendException() throws ApiException {
    final SmsApi.SendSmsMessageRequest messageRequest = mock(SmsApi.SendSmsMessageRequest.class);

    when(codeGenerator.generateVerificationCode()).thenReturn("123456");
    when(client.sendSmsMessage(any())).thenReturn(messageRequest);
    when(messageRequest.execute()).thenThrow(ApiException.builder().build());

    assertThrows(CompletionException.class, () -> sender.sendVerificationCode(MessageTransport.SMS,
        PhoneNumberUtil.getInstance().getExampleNumber("US"),
        Locale.LanguageRange.parse("en"),
        ClientType.IOS).join());
  }

  @Test
  void sendAndVerify() throws ApiException {
    final SmsApi.SendSmsMessageRequest messageRequest = mock(SmsApi.SendSmsMessageRequest.class);
    final SmsResponse response = mock(SmsResponse.class);
    final SmsResponseDetails details = mock(SmsResponseDetails.class);
    final MessageStatus status = mock(MessageStatus.class);

    when(codeGenerator.generateVerificationCode()).thenReturn("123456");
    when(client.sendSmsMessage(any())).thenReturn(messageRequest);
    when(messageRequest.execute()).thenReturn(response);
    when(response.getMessages()).thenReturn(List.of(details));
    when(details.getStatus()).thenReturn(status);
    when(details.getMessageId()).thenReturn("3984417919333868671474");
    when(status.getGroupId()).thenReturn(1);

    final byte[] senderData = sender.sendVerificationCode(MessageTransport.SMS,
            PhoneNumberUtil.getInstance().getExampleNumber("US"),
            Locale.LanguageRange.parse("en"), ClientType.IOS)
        .join()
        .senderData();

    assertTrue(sender.checkVerificationCode("123456", senderData).join());
  }

  @Test
  void sendRejectedSignalsBlocked() throws ApiException {
    final SmsApi.SendSmsMessageRequest messageRequest = mock(SmsApi.SendSmsMessageRequest.class);
    final SmsResponse response = mock(SmsResponse.class);
    final SmsResponseDetails details = mock(SmsResponseDetails.class);
    final MessageStatus status = mock(MessageStatus.class);

    when(codeGenerator.generateVerificationCode()).thenReturn("123456");
    when(client.sendSmsMessage(any())).thenReturn(messageRequest);
    when(messageRequest.execute()).thenReturn(response);
    when(response.getMessages()).thenReturn(List.of(details));
    when(details.getStatus()).thenReturn(status);
    when(details.getMessageId()).thenReturn(RandomStringUtils.randomNumeric(22));
    when(status.getId()).thenReturn(87);

    final CompletionException completionException = assertThrows(CompletionException.class,
        () -> sender.sendVerificationCode(MessageTransport.SMS,
            PhoneNumberUtil.getInstance().getExampleNumber("US"),
            Locale.LanguageRange.parse("en"), ClientType.UNKNOWN)
        .join());

    assertInstanceOf(SenderFraudBlockException.class, completionException.getCause());
  }
}
