package com.berkayb.soundconnect.shared.messaging.events.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

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
	
	// Exchange ve publish routing key'i application.yml dan aliyoruz
	@Value("${app.messaging.notification.exchange}")
	private String exchange;
	
	@Value("${app.messaging.notification.publishRoutingKey:notification.event}")
	private String publishRoutingKey;
	
	// NotificationInboundEvent mesajini RabbitMQ'ya publish eder.
	
	public void publish(NotificationInboundEvent event) {
		try{
			rabbitTemplate.convertAndSend(exchange, publishRoutingKey, event);
			log.info("Notification published to RabbitMQ. exchange={}, routingKey={}, recipient={}, type={}",
			        exchange, publishRoutingKey, event.recipientId(), event.type());
		} catch (Exception e) {
			log.error("Notification publish failed! recipient={}, type={}, err={}",
			          event.recipientId(), event.type(), e.toString());
			// fallback-dlq-monitoring logic
		}
	}
}
