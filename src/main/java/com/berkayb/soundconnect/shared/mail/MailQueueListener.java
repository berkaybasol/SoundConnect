package com.berkayb.soundconnect.shared.mail;

import com.berkayb.soundconnect.shared.mail.service.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class MailQueueListener {
	
	private final MailService mailService;
	
	@RabbitListener(queues = MailProducerImpl.VERIFICATION_MAIL_QUEUE)
	public void handleVerificationMail(Map<String, Object> message) {
		String email = (String) message.get("email");
		String token = (String) message.get("token");
		mailService.sendVerificationMail(email, token);
	}
}