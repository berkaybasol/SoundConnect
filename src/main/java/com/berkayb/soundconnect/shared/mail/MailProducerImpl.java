package com.berkayb.soundconnect.shared.mail;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MailProducerImpl implements MailProducer {
	private final RabbitTemplate rabbitTemplate;
	public static final String VERIFICATION_MAIL_QUEUE = "email-verification-queue";
	
	@Override
	public void sendVerificationMail(String email, String verificatiyonToken) {
	// Mesaji bir Map veya DTO ile kuyruga gonderiyoruz.
		Map<String, Object> message = new HashMap<>();
		message.put("email", email);
		message.put("token", verificatiyonToken);
		rabbitTemplate.convertAndSend(VERIFICATION_MAIL_QUEUE, message);
	}
}