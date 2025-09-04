package com.berkayb.soundconnect.shared.mail.producer;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;


// ------------------- DİKKAT: TERİMLERDE TAKILIRSAN 'DOCS/RABBITMQ.MD' DOSYASINA GİT! -------------------



@Service
@RequiredArgsConstructor
@Slf4j
public class MailProducerImpl implements MailProducer {
	private final RabbitTemplate rabbitTemplate; // Spring Boot'un mesaj gonderme arayuzu
	
	@Value("${mail.queue.exchange:mail-exchange}")
	private String mailExchange;
	
	@Value("${mail.queue.routingKey:mail.send}")
	private String mailRoutingKey;
	
	
	
	@Override
	public void send(MailSendRequest request) {
		try {
			rabbitTemplate.convertAndSend(mailExchange, mailRoutingKey, request);
			log.info("Mail job queued: kind={}, to={}, subject={}", request.kind(), request.to(), request.subject());
		} catch (Exception e) {
			log.error("Failed to queue mail job: kind={}, to={}, subject={}", request.kind(), request.to(), request.subject(), e);
			
		}
	}
}