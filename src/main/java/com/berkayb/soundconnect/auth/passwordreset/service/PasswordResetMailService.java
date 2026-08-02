package com.berkayb.soundconnect.auth.passwordreset.service;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailContentBuilder;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
public class PasswordResetMailService {

	private static final String SUBJECT = "Şifre Sıfırlama Kodunuz";

	private final MailProducer mailProducer;
	private final MailContentBuilder mailContentBuilder;

	@Value("${otp.ttl.minutes:${mailersend.otp-validity-minutes:3}}")
	private int otpValidityMinutes;

	public PasswordResetMailService(
			MailProducer mailProducer,
			MailContentBuilder mailContentBuilder
	) {
		this.mailProducer = mailProducer;
		this.mailContentBuilder = mailContentBuilder;
	}

	/**
	 * {@link MailProducer#send(MailSendRequest)} waits for RabbitMQ publisher
	 * confirmation and rejects NACK, return and timeout outcomes. The request
	 * thread therefore reports success only after the reset job is durably
	 * accepted by the broker.
	 */
	public void queueResetCode(String recipient, String code) {
		mailProducer.send(buildRequest(recipient, code));
	}

	private MailSendRequest buildRequest(String recipient, String code) {
		String htmlBody = mailContentBuilder.buildPasswordResetMail(code, otpValidityMinutes);
		String textBody = """
				Şifrenizi sıfırlamak için kodunuz: %s
				Bu kod %d dakika boyunca geçerlidir ve yalnızca bir kez kullanılabilir.
				Bu isteği siz yapmadıysanız bu e-postayı dikkate almayın.
				""".formatted(code, otpValidityMinutes);

		return new MailSendRequest(
				recipient,
				SUBJECT,
				htmlBody,
				textBody,
				MailKind.PASSWORD_RESET,
				Map.of(
						"requestId", UUID.randomUUID().toString(),
						"validityMinutes", otpValidityMinutes
				)
		);
	}
}
