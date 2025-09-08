package com.berkayb.soundconnect.shared.mail.producer;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component
@RequiredArgsConstructor
public class MailRetryPublisher {
	
	private final RabbitTemplate rabbitTemplate;
	
	@Value("${mail.delayed.exchange:mail.delayed}")
	private String delayedExchange;
	
	@Value("${mail.routingKey:mail.send}")
	private String routingKey;
	
	/**
	 * Gecikme ile yeniden publish eder.
	 * attemptInfo: log için küçük metin ("deaths=2", "retryAfter=12s" vs)
	 */
	public void publishWithDelay(MailSendRequest request, long delayMs, String attemptInfo) {
		long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, delayMs) / 10 + 1); // %0–10 jitter
		long finalDelay = delayMs + jitter;
		
		MessagePostProcessor mpp = message -> {
			message.getMessageProperties().setHeader("x-delay", finalDelay);
			// İzlenebilirlik için küçük etiketler:
			message.getMessageProperties().setHeader("sc-retry", "true");
			message.getMessageProperties().setHeader("sc-retry-note", attemptInfo);
			return message;
		};
		
		rabbitTemplate.convertAndSend(delayedExchange, routingKey, request, mpp);
		
		log.info("Mail retry scheduled -> delay={}ms, note={}, to={}, subject={}",
		         finalDelay,
		         attemptInfo,
		         maskForLog(request.to()),
		         request.subject());
	}
	
	private String maskForLog(String email) {
		if (email == null) return "null";
		int at = email.indexOf('@');
		if (at <= 1) return "***";
		String local = email.substring(0, at);
		String domain = email.substring(at + 1);
		String maskedLocal = local.charAt(0) + "***";
		int dot = domain.indexOf('.');
		String maskedDomain = (dot > 1)
				? domain.charAt(0) + "***" + domain.substring(dot)
				: domain.charAt(0) + "***";
		return maskedLocal + "@" + maskedDomain;
	}
}