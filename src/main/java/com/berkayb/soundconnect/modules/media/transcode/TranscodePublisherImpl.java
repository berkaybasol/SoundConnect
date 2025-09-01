package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.dto.request.VideoHlsRequest;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.UUID;

//---------------------------------Terimlerde Takilirsan RabbitMQ.md'ye bak---------------------------------------------

/**
 * Video HLS transcode isteklerini RabbitMQ'ya publish eden servis.
 * - Exchange/routingKey ayarları YAML'dan okunur.
 * - Publisher confirm & returns callback’leri bağlanır.
 * - Mesajlar "persistent" (diskte kalıcı) olarak publish edilir.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TranscodePublisherImpl implements TranscodePublisher {
	
	private final RabbitTemplate rabbitTemplate;
	
	// Exchange adı
	@Value("${rabbitmq.media.exchange}")
	private String exchange;
	
	// Mesajın gideceği routing key  (DÜZELTİLDİ)
	@Value("${rabbitmq.media.routingKeys.videoHlsRequest}")
	private String videoHlsRoutingKey;
	
	// Uygulama ayağa kalkınca callback’leri bağla
	@PostConstruct
	void init() {
		// Unroutable mesajlar geri dönsün
		rabbitTemplate.setMandatory(true);
		
		// Broker ACK/NACK
		rabbitTemplate.setConfirmCallback(this::onConfirm);
		
		// Route edilemeyen mesajlar
		rabbitTemplate.setReturnsCallback(this::onReturned);
		
		log.info("[transcode-publisher] initialized exchange={} routingKey={}", exchange, videoHlsRoutingKey);
	}
	
	// Dışarıdan assetId, sourceKey ve hlsPrefix alıp kuyruğa publish eder
	@Override
	public void publishVideoHls(UUID assetId, String sourceKey, String hlsPrefix) {
		// Parametre kontrolü
		if (assetId == null || !StringUtils.hasText(sourceKey) || !StringUtils.hasText(hlsPrefix)) {
			throw new SoundConnectException(ErrorType.INTERNAL_ERROR);
		}
		
		// DTO payload (consumer ile anlaşmalı şema)
		var payload = new VideoHlsRequest(
				assetId.toString(),
				sourceKey,
				hlsPrefix,
				Instant.now().toString(),
				1
		);
		
		// Mesaj özellikleri
		MessagePostProcessor mpp = message -> {
			message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			message.getMessageProperties().setHeader("eventType", "VIDEO_HLS_REQUEST");
			message.getMessageProperties().setHeader("assetId", assetId.toString());
			message.getMessageProperties().setHeader("schemaVersion", 1);
			return message;
		};
		
		// Confirm takibi için correlation id = assetId
		var cd = new CorrelationData(assetId.toString());
		
		try {
			rabbitTemplate.convertAndSend(exchange, videoHlsRoutingKey, payload, mpp, cd);
			log.info("[transcode-publisher] queued VIDEO_HLS_REQUEST assetId={} sourceKey={} hlsPrefix={}",
			         assetId, sourceKey, hlsPrefix);
		} catch (Exception e) {
			log.error("[transcode-publisher] publish failed assetId={} err={}", assetId, e.getMessage(), e);
			throw new SoundConnectException(ErrorType.INTERNAL_ERROR);
		}
	}
	
	// Confirm callback
	private void onConfirm(CorrelationData correlationData, boolean ack, String cause) {
		String corrId = correlationData != null ? correlationData.getId() : "null";
		if (ack) {
			log.debug("[transcode-publisher] confirm ACK corrId={}", corrId);
		} else {
			log.error("[transcode-publisher] confirm NACK corrId={} cause={}", corrId, cause); // DÜZELTİLDİ
		}
	}
	
	// Return callback (unroutable)
	private void onReturned(ReturnedMessage returned) {
		log.error("[transcode-publisher] returned message exchange={} routingKey={} replyCode={} replyText={} body={}",
		          returned.getExchange(),
		          returned.getRoutingKey(),
		          returned.getReplyCode(),
		          returned.getReplyText(),
		          new String(returned.getMessage().getBody()));
	}
}