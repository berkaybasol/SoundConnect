package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.shared.config.MailQueueConfig;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Mail joblarini RabbitMQ kuyruktan dinler ve isler
 * Amac:
 * - Gelen her mail isini, MailerSend'e yollar.
 * - hata alirsa DLQ'ya gonderir.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailJobConsumer {
	private final MailSenderClient mailSenderClient;
	
	
	/**
	 * Mail kuyrugunu dinler her mesaj islenir. hata olursa DLQ'ya yonlendirilir.
	 * @param request kuyruktan gelen is (MailSendRequest)
	 * @param tag RabbitMQ mesajinin delivery tag'i (mesaj onay/reject icin)
	 * @param channel RabbitMQ baglanti kanali (message acknowledge/reject islemleri icin
	 */
	@RabbitListener(queues = MailQueueConfig.MAIL_QUEUE) // RabbitMQ burayi dinliyo
	public void listenMailJobs(MailSendRequest request, @Header(AmqpHeaders.DELIVERY_TAG) long tag, Channel channel) {
		try {
			// islenen mail bilgisini logla
			log.info("Processing mail job: kind={}, to={}, subject={}", request.kind(), request.subject());
			// maili gonder
			mailSenderClient.send(
					request.to(),
					request.subject(),
					request.textBody(),
					request.htmlBody()
			);
			log.debug("Mail sent: to={}, kind={}, subject={}", request.to(), request.kind(), request.subject());
			// basariyla islendiyse mesaji RabbitMQ'da "is bitti" olarak isaretle
			channel.basicAck(tag,false);
		} catch (Exception e) {
			log.error("Failed to process mail job: kind={}, to={}, subject={}, err={}", request.kind(), request.to(),
			          request.subject(), e.toString());
			try {
				// hata olursa mesaji tekrar main kuyruqa yollama DLQ'ya yonlendir.
				channel.basicReject(tag,false); // tekrar deneme yok, DLQ'ya duser
			} catch (Exception ex) {
				log.error("DLQ'ya aktararamadik. Manuel kontrol gerekli", ex);
			}
		}
	}
}