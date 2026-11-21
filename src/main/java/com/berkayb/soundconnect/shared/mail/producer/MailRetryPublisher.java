package com.berkayb.soundconnect.shared.mail.producer;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.config.MailRetryTopology;
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
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class MailRetryPublisher {
	
	private final RabbitTemplate rabbitTemplate;
	private RabbitTemplate retryRabbitTemplate;

	@Value("${mail.queueName:mail.queue}")
	private String mailQueueName;
	
	@Value("${mail.producer.confirmTimeoutSec:5}")
	private long confirmTimeoutSec;

	@PostConstruct
	void validateConfiguration() {
		if (confirmTimeoutSec < 1 || confirmTimeoutSec > 30) {
			throw new IllegalStateException("mail.producer.confirmTimeoutSec must be between 1 and 30");
		}
		MailRetryTopology.validateMainQueue(mailQueueName);
		// Keep shared callbacks/settings untouched. Native exact-queue routing
		// supports mandatory returns, unlike the removed delayed-exchange plugin.
		retryRabbitTemplate = new RabbitTemplate(rabbitTemplate.getConnectionFactory());
		retryRabbitTemplate.setMessageConverter(rabbitTemplate.getMessageConverter());
		retryRabbitTemplate.setMandatory(true);
	}
	
	/**
	 * Gecikme ile yeniden publish eder.
	 * attemptInfo: log için küçük metin ("attempt=2", "attempt=2,429" vs)
	 */
	public void publishWithDelay(MailSendRequest request, long delayMs, int retryAttempt, String attemptInfo) {
        com.berkayb.soundconnect.shared.recovery.RecoveryRuntime.requireOperational();
		if (retryAttempt < 1 || retryAttempt > 20) {
			throw new IllegalArgumentException("retryAttempt must be between 1 and 20");
		}
		long finalDelay = MailRetryTopology.bucketDelayMs(delayMs);
		String retryQueue = MailRetryTopology.queueName(mailQueueName, finalDelay);
		String correlationId = UUID.randomUUID().toString();
		CorrelationData correlationData = new CorrelationData(correlationId);
		
		MessagePostProcessor mpp = message -> {
			message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			message.getMessageProperties().setCorrelationId(correlationId);
			// İzlenebilirlik için küçük etiketler:
			message.getMessageProperties().setHeader(MailJobHelper.RETRY_MARKER_HEADER, true);
			message.getMessageProperties().setHeader(MailJobHelper.RETRY_ATTEMPT_HEADER, retryAttempt);
			return message;
		};
		
		try {
			// Quorum delay queues retain expired messages until the future target
			// confirms. A missing delay queue is returned now, before source ACK.
			retryRabbitTemplate.execute(channel -> channel.queueDeclarePassive(mailQueueName));
			retryRabbitTemplate.convertAndSend("", retryQueue, request, mpp, correlationData);
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
		
		// Arbitrary caller notes and message/recipient content never enter headers/logs.
		log.info("Mail retry scheduled -> attempt={}, delay={}ms", retryAttempt, finalDelay);
	}
	
	private String maskForLog(String email) {
		if (email == null) return "null";
		int at = email.indexOf('@');
		if (at <= 1 || at == email.length() - 1) return "***";
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
