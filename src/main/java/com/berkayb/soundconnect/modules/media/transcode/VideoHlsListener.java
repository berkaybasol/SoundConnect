package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.dto.request.VideoHlsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * RabbitMQ Consumer
 * - Queue ${rabbitmq.media.queues.videHls}
 * - Mesaj govdesi JSON (VideoHlsRequest) Jackson ile parse edilir.
 * - Basariliysa -> workflow.process()
 * - Hataliysa AmqpRejectAndDontRequeueException -> DLX/DLQ'ya duser (requeue = false)
 */

@Component
@RequiredArgsConstructor
@Slf4j
public class VideoHlsListener {
	private final VideoHlsWorkflow workflow;
	private final ObjectMapper objectMapper; // spring boot otomatik sagliyor <3
	
	@RabbitListener(
			queues = "${rabbitmq.media.queues.videoHls}",
			concurrency = "${rabbitmq.media.listenerConcurrency:1-3}"
	)
	public void onMessage(Message message) {
		// headerlardan useful seyleri cek (diagnostic/log)
		String corrId = message.getMessageProperties().getCorrelationId();
		String routingKey = message.getMessageProperties().getReceivedRoutingKey();
		
		try {
			// JSON -> DTO (ham byte[]'dan okuyoruz guvenli ve esnek olmasi icin
			byte[] body = message.getBody();
			String json = new String(body, StandardCharsets.UTF_8);
			VideoHlsRequest req = objectMapper.readValue(json, VideoHlsRequest.class);
			
			log.info("[hls-listener] received corrId={} rk={} assetId={} hlsPrefix={}", corrId, routingKey, req.assetId(), req.hlsPrefix());
			
			// is akisi
			workflow.process(req);
			
			// buraya gelmissek ok. spring otomatik olarak ACK'ler
			log.info("[hls-listener] done corrId={} assetId={}", corrId, req.assetId());
		} catch (Exception e) {
			// Reque etme DLX'e dussun
			log.error("[hls-listener] FAIL corrId={} rk={} err={}", corrId, routingKey, e.getMessage(), e);
			throw new AmqpRejectAndDontRequeueException("workflow failed: " + e.getMessage(), e);
		}
	}
}