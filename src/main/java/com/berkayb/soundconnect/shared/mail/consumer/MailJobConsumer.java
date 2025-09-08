package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.berkayb.soundconnect.shared.mail.producer.MailRetryPublisher;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class MailJobConsumer {
	
	private final MailSenderClient mailSenderClient;
	private final MailJobHelper helper;
	private final MailRetryPublisher retryPublisher;
	
	@Value("${mail.idempotencyTtlSec:900}")
	private long idempotencyTtlSec;
	
	@Value("${mail.lockTtlSec:300}")
	private long lockTtlSec;
	
	@Value("${mail.maxRedeliveries:5}")
	private int maxRedeliveries;
	
	@Value("${mail.retry.delaysMs:3000,10000,30000}")
	private List<Long> delaysMs;
	
	@Value("${mail.retry.useRetryAfter:true}")
	private boolean useRetryAfter;
	
	@RabbitListener(queues = "${mail.queueName}", containerFactory = "mailListenerFactory")
	public void listenMailJobs(MailSendRequest request,
	                           @Header(AmqpHeaders.DELIVERY_TAG) long tag,
	                           @Headers Map<String, Object> headers,
	                           Channel channel) {
		final String maskedTo = helper.maskEmail(request.to());
		
		final String idKey  = helper.buildIdemKey(request);
		final String sentKey = "mail:sent:" + idKey;
		final String lockKey = "mail:lock:" + idKey;
		
		try {
			// Already sent? -> ACK & exit
			if (helper.isAlreadySent(sentKey)) {
				log.info("Mail Job SKIPPED (already sent): kind={}, to={}, subject={}",
				         request.kind(), maskedTo, request.subject());
				channel.basicAck(tag, false);
				return;
			}
			
			// Concurrency lock
			boolean gotLock = helper.acquireLock(lockKey, Duration.ofSeconds(lockTtlSec));
			if (!gotLock) {
				log.info("Mail job BUSY (locked) -> requeue: kind={}, to={}, subject={}",
				         request.kind(), maskedTo, request.subject());
				channel.basicReject(tag, true);
				return;
			}
			
			log.info("processing mail job: kind={}, to={}, subject={}", request.kind(), maskedTo, request.subject());
			
			// Send
			mailSenderClient.send(request.to(), request.subject(), request.textBody(), request.htmlBody());
			
			// Success -> mark sent + release lock + ACK
			helper.markSent(sentKey, Duration.ofSeconds(idempotencyTtlSec));
			helper.releaseLock(lockKey);
			log.debug("Mail sent OK -> to={}, kind={}, subject={}", maskedTo, request.kind(), request.subject());
			channel.basicAck(tag, false);
			
		} catch (Exception e) {
			int deaths = helper.redeliveryCount(headers);
			boolean transientErr = helper.isTransient(e);
			boolean limitOk = deaths < maxRedeliveries;
			
			// Lock'u mutlaka sal
			try { helper.releaseLock(lockKey); } catch (Exception ignore) {}
			
			if (transientErr && limitOk) {
				long delay = helper.chooseDelayMs(e, deaths, delaysMs, useRetryAfter);
				String note = "deaths=" + deaths + (helper.isRateLimited(e) ? ",429" : "");
				
				// --- YENİ SIRALAMA: önce gecikmeli publish dene, sonra ACK ---
				try {
					// Publish retry message (delayed)
					retryPublisher.publishWithDelay(request, delay, note);
					
					// Hata logunu requeue=true ile yaz
					helper.logErrorForSend(request, e, true);
					
					// Publish BAŞARILI → orijinal mesajı ACK'le
					try {
						channel.basicAck(tag, false);
					} catch (Exception ackEx) {
						// ACK başarısızsa duplicate riski idempotency ile tolere edilir
						log.error("ACK failed AFTER retry publish. Message may redeliver; idempotency will guard. err={}",
						          ackEx.toString());
					}
				} catch (Exception pubEx) {
					// Retry publish BAŞARISIZ → orijinal mesajı kuyrukta tut (requeue)
					log.error("Retry publish FAILED -> keeping original message in queue. err={}", pubEx.toString());
					try {
						channel.basicReject(tag, true);
					} catch (Exception rejectEx) {
						log.error("Reject(requeue) also FAILED, manual intervention needed. err={}", rejectEx.toString());
					}
				}
				return;
			}
			
			// Poison veya limit aşıldı -> DLQ
			helper.logErrorForSend(request, e, false);
			try {
				channel.basicReject(tag, false);
			} catch (Exception ackEx) {
				log.error("Reject(false) FAILED, manual intervention needed. err={}", ackEx.toString());
			}
		}
	}
}