package com.berkayb.soundconnect.shared.messaging.events.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/*
Sistem genelinde bildirim (NotificationInboundEvent) gondermek icin kullanilan merkezi RabbitMQ Publisher/Producer
sinifidir.
- Producer tarafi eventleri notification exchange'e publish eder.
- Consumer tarafi notification modulundeki @RabbitListener ile eventleri dinler

Bu class diger modullerde notification ihtiyaci oldugunda DI ile inject edilip kullanilir.
 */
@Component
@RequiredArgsConstructor
@Profile("!test")
@Slf4j
public class NotificationProducer {
	
	private final RabbitTemplate rabbitTemplate;
	private final NotificationPublisherProperties publisherProperties;
	
	// Exchange ve publish routing key'i application.yml dan aliyoruz
	@Value("${app.messaging.notification.exchange}")
	private String exchange;
	
	@Value("${app.messaging.notification.publishRoutingKey:notification.event}")
	private String publishRoutingKey;

	@PostConstruct
	void validateConfiguration() {
		Duration publisherConfirmTimeout = publisherProperties.getPublisherConfirmTimeout();
		if (publisherConfirmTimeout == null
				|| publisherConfirmTimeout.compareTo(Duration.ofSeconds(1)) < 0
				|| publisherConfirmTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
			throw new IllegalStateException(
					"app.messaging.notification.publisher-confirm-timeout must be between 1s and 30s"
			);
		}
	}
	
	// NotificationInboundEvent mesajini RabbitMQ'ya publish eder.
	
	public void publish(NotificationInboundEvent event) {
		try{
			rabbitTemplate.convertAndSend(exchange, publishRoutingKey, event);
			log.info("Notification published to RabbitMQ. exchange={}, routingKey={}, eventId={}, type={}",
			        exchange, publishRoutingKey, event.eventId(), event.type());
		} catch (Exception e) {
			log.error("Notification publish failed. eventId={}, type={}, exceptionType={}",
			          event.eventId(), event.type(), e.getClass().getName());
			// fallback-dlq-monitoring logic
		}
	}

	/**
	 * Publishes a persistent message and returns only after RabbitMQ confirms it
	 * was accepted and routed. Unlike the legacy {@link #publish} method this
	 * method deliberately propagates NACK, return, timeout and connection errors
	 * so a durable caller can retain/retry its work item.
	 */
	public void publishConfirmed(NotificationInboundEvent event) {
		Objects.requireNonNull(event, "event is required");
		UUID eventId = Objects.requireNonNull(event.eventId(), "event.eventId is required");
		CorrelationData correlationData = new CorrelationData(eventId.toString());
		MessagePostProcessor persistentHeaders = message -> {
			message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			message.getMessageProperties().setCorrelationId(eventId.toString());
			message.getMessageProperties().setHeader("eventId", eventId.toString());
			message.getMessageProperties().setHeader("eventType", "NOTIFICATION_INBOUND");
			message.getMessageProperties().setHeader("schemaVersion", 2);
			return message;
		};

		try {
			rabbitTemplate.convertAndSend(
					exchange,
					publishRoutingKey,
					event,
					persistentHeaders,
					correlationData
			);
			CorrelationData.Confirm confirm = correlationData.getFuture().get(
					publisherProperties.getPublisherConfirmTimeout().toMillis(),
					TimeUnit.MILLISECONDS
			);
			ReturnedMessage returned = correlationData.getReturned();
			if (!confirm.isAck() || returned != null) {
				throw new AmqpException(
						"Notification broker rejected or returned eventId=" + eventId
				);
			}
			log.debug("Notification publish confirmed. eventId={}, type={}", eventId, event.type());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AmqpException("Interrupted while waiting for notification broker confirmation", exception);
		} catch (TimeoutException | ExecutionException exception) {
			throw new AmqpException("Notification broker confirmation failed", exception);
		} catch (AmqpException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new AmqpException("Notification publish failed", exception);
		}
	}
}
