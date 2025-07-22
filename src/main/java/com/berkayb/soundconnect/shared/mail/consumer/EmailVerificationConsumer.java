package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.shared.mail.dto.EmailVerificationMessage;
import com.berkayb.soundconnect.shared.mail.service.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EmailVerificationConsumer {
	
	private final MailService mailService;
	
	// Kuyruktan mesajı dinler, DTO geldiğinde ilgili servisi çağırır.
	@RabbitListener(queues = "email-verification-queue")
	public void listenVerificationMail(EmailVerificationMessage message) {
		mailService.sendVerificationMail(message.email(), message.token());
	}
}