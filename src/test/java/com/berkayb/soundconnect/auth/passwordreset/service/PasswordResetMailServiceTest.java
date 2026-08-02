package com.berkayb.soundconnect.auth.passwordreset.service;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailContentBuilder;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetMailServiceTest {

	@Mock MailProducer mailProducer;
	@Mock MailContentBuilder mailContentBuilder;

	private PasswordResetMailService service;

	@BeforeEach
	void setUp() {
		service = new PasswordResetMailService(
				mailProducer,
				mailContentBuilder);
		ReflectionTestUtils.setField(service, "otpValidityMinutes", 3);
	}

	@Test
	void queuesPasswordResetTemplateThroughGenericMailProducer() {
		when(mailContentBuilder.buildPasswordResetMail("123456", 3))
				.thenReturn("<html>reset-code</html>");

		service.queueResetCode("user@example.com", "123456");

		ArgumentCaptor<MailSendRequest> captor = ArgumentCaptor.forClass(MailSendRequest.class);
		verify(mailProducer).send(captor.capture());
		MailSendRequest request = captor.getValue();

		assertThat(request.to()).isEqualTo("user@example.com");
		assertThat(request.subject()).isEqualTo("Şifre Sıfırlama Kodunuz");
		assertThat(request.kind()).isEqualTo(MailKind.PASSWORD_RESET);
		assertThat(request.htmlBody()).isEqualTo("<html>reset-code</html>");
		assertThat(request.textBody()).contains("123456", "3 dakika", "yalnızca bir kez");
		assertThat(request.params())
				.containsEntry("validityMinutes", 3)
				.containsKey("requestId");
		assertThat(request.params().values()).doesNotContain("123456");
		verify(mailContentBuilder).buildPasswordResetMail("123456", 3);
	}

	@Test
	void brokerPublishFailureIsPropagatedToTheRequestFlow() {
		when(mailContentBuilder.buildPasswordResetMail("123456", 3))
				.thenReturn("<html>reset-code</html>");
		doThrow(new IllegalStateException("broker unavailable"))
				.when(mailProducer).send(org.mockito.ArgumentMatchers.any());

		assertThatThrownBy(() ->
				service.queueResetCode("user@example.com", "123456"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("broker unavailable");
	}
}
