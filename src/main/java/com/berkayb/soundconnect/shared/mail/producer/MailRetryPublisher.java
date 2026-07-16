package com.berkayb.soundconnect.shared.mail.producer;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class MailRetryPublisher {
	
	private final RabbitTemplate rabbitTemplate;
	
	@Value("${mail.delayed.exchange:mail.delayed}")
	private String delayedExchange;
	
	@Value("${mail.routingKey:mail.send}")
	private String routingKey;

	@Value("${mail.producer.confirmTimeoutSec:5}")
	private long confirmTimeoutSec;

	@PostConstruct
	void validateConfiguration() {
		if (confirmTimeoutSec < 1 || confirmTimeoutSec > 30) {
			throw new IllegalStateException("mail.producer.confirmTimeoutSec must be between 1 and 30");
		}
	}
	
	/**
	 * Gecikme ile yeniden publish eder.
	 * attemptInfo: log için küçük metin ("attempt=2", "attempt=2,429" vs)
	 */
	public void publishWithDelay(MailSendRequest request, long delayMs, int retryAttempt, String attemptInfo) {
		if (retryAttempt < 1 || retryAttempt > 20) {
			throw new IllegalArgumentException("retryAttempt must be between 1 and 20");
		}
		if (delayMs < 1 || delayMs > 86_400_000L) {
			throw new IllegalArgumentException("delayMs must be between 1ms and 24h");
		}
		long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, delayMs) / 10 + 1); // %0–10 jitter
		long finalDelay = delayMs + jitter;
		String correlationId = UUID.randomUUID().toString();
		CorrelationData correlationData = new CorrelationData(correlationId);
		
		MessagePostProcessor mpp = message -> {
			message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			message.getMessageProperties().setCorrelationId(correlationId);
			message.getMessageProperties().setHeader("x-delay", finalDelay);
			// İzlenebilirlik için küçük etiketler:
			message.getMessageProperties().setHeader(MailJobHelper.RETRY_MARKER_HEADER, true);
			message.getMessageProperties().setHeader(MailJobHelper.RETRY_ATTEMPT_HEADER, retryAttempt);
			message.getMessageProperties().setHeader("sc-retry-note", attemptInfo);
			return message;
		};
		
		try {
			rabbitTemplate.convertAndSend(delayedExchange, routingKey, request, mpp, correlationData);
			CorrelationData.Confirm confirm = correlationData.getFuture()
					.get(confirmTimeoutSec, TimeUnit.SECONDS);
			ReturnedMessage returned = correlationData.getReturned();
			if (!confirm.isAck() || returned != null) {
				throw new AmqpException("Retry publish was not accepted by the broker");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AmqpException("Interrupted while waiting for retry publish confirmation", exception);
		} catch (AmqpException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new AmqpException("Retry publish confirmation failed", exception);
		}
		
		log.info("Mail retry scheduled -> attempt={}, delay={}ms, note={}, to={}",
		         retryAttempt,
		         finalDelay,
		         attemptInfo,
		         maskForLog(request.to()));
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
