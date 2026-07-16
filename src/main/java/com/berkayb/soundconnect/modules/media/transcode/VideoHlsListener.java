package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.dto.request.VideoHlsRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(
		prefix = "media.transcode.worker",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = false
)
@RequiredArgsConstructor
@Slf4j
public class VideoHlsListener {
	private final VideoHlsWorkflow workflow;
	private final ObjectMapper objectMapper; // spring boot otomatik sagliyor <3
	private final MediaHlsWorkExecutor workExecutor;
	
	@RabbitListener(
			queues = "${rabbitmq.media.queues.videoHls}",
			concurrency = "${rabbitmq.media.listenerConcurrency:1}",
			containerFactory = "mediaVideoHlsListenerFactory"
	)
	public void onMessage(Message message, Channel channel) {
		// headerlardan useful seyleri cek (diagnostic/log)
		String corrId = message.getMessageProperties().getCorrelationId();
		String routingKey = message.getMessageProperties().getReceivedRoutingKey();
		
		boolean acknowledged = false;
		boolean reserved = false;
		boolean durableClaimed = false;
		try {
			// JSON -> DTO (ham byte[]'dan okuyoruz guvenli ve esnek olmasi icin
			byte[] body = message.getBody();
			String json = new String(body, StandardCharsets.UTF_8);
			VideoHlsRequest req = objectMapper.readValue(json, VideoHlsRequest.class);
			
			log.info("[hls-listener] received corrId={} rk={} assetId={} hlsPrefix={}", corrId, routingKey, req.assetId(), req.hlsPrefix());
			
			if (!workExecutor.tryReserve()) {
				boolean requeued = workflow.deferSignal(req);
				channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
				acknowledged = true;
				log.info("[hls-listener] capacity deferred corrId={} assetId={} requeued={}",
						corrId, req.assetId(), requeued);
				return;
			}
			reserved = true;
			var claimed = workflow.claim(req);

			if (claimed.isEmpty()) {
				channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
				acknowledged = true;
				log.info("[hls-listener] duplicate/stale signal acknowledged corrId={} assetId={}",
						corrId, req.assetId());
				return;
			}
			var claimedWork = claimed.orElseThrow();
			durableClaimed = true;
			reserved = false; // submitReserved always consumes/releases the reservation
			boolean submitted;
			try {
				submitted = workExecutor.submitReserved(claimedWork);
			} catch (RuntimeException submissionFailure) {
				boolean abandoned = workflow.abandonClaimForRetry(claimedWork);
				channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
				acknowledged = true;
				log.warn("[hls-listener] handoff failed; durable claim returned corrId={} assetId={} abandoned={} type={}",
						corrId, req.assetId(), abandoned,
						submissionFailure.getClass().getSimpleName());
				return;
			}

			if (!submitted) {
				boolean abandoned = workflow.abandonClaimForRetry(claimedWork);
				channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
				acknowledged = true;
				log.warn("[hls-listener] handoff rejected; durable claim returned corrId={} assetId={} abandoned={}",
						corrId, req.assetId(), abandoned);
				return;
			}

			// ACK only after the background executor has accepted the already durable
			// claim. A multi-hour FFmpeg job therefore never depends on Rabbit's
			// consumer acknowledgement timeout, while shutdown cannot strand a fresh
			// PROCESSING claim without a worker.
			channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
			acknowledged = true;
			log.info("[hls-listener] handed off corrId={} assetId={} submitted={}",
					corrId, req.assetId(), submitted);
		} catch (Exception e) {
			if (durableClaimed && !acknowledged) {
				// A submitted worker or the exact-token abandonment state now owns the
				// job. Do not convert an ACK/channel failure into a permanent DLQ reject.
				log.error("[hls-listener] post-claim delivery failure corrId={} rk={} err={}",
						corrId, routingKey, e.getMessage(), e);
				if (e instanceof RuntimeException runtime) throw runtime;
				throw new IllegalStateException("post-claim delivery failure", e);
			}
			if (acknowledged) {
				// Failure is already represented by PROCESSING/HLS_CLEANUP and durable
				// lease recovery. Re-throwing after ACK cannot provide delivery safety.
				log.error("[hls-listener] claimed work failed corrId={} rk={} err={}",
						corrId, routingKey, e.getMessage(), e);
				return;
			}
			log.error("[hls-listener] FAIL corrId={} rk={} err={}", corrId, routingKey, e.getMessage(), e);
			throw new AmqpRejectAndDontRequeueException("claim failed: " + e.getMessage(), e);
		} finally {
			if (reserved) workExecutor.releaseReservation();
		}
	}
}
