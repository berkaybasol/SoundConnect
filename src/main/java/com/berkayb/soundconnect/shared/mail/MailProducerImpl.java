package com.berkayb.soundconnect.shared.mail;

import com.berkayb.soundconnect.shared.mail.dto.EmailVerificationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MailProducerImpl implements MailProducer {
	private final RabbitTemplate rabbitTemplate;
	public static final String VERIFICATION_MAIL_QUEUE = "email-verification-queue";
	
	@Override
	public void sendVerificationMail(String email, String verificatiyonToken) {
		EmailVerificationMessage message = new EmailVerificationMessage(email, verificatiyonToken);
		rabbitTemplate.convertAndSend(VERIFICATION_MAIL_QUEUE, message);
	}
}