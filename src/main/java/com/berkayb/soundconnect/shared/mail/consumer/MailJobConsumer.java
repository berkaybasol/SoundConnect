package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.shared.config.MailQueueConfig;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MailJobConsumer {
	private final MailSenderClient mailSenderClient;
	
	@RabbitListener(queues = MailQueueConfig.MAIL_QUEUE)
	public void listenMailJobs(MailSendRequest request) {
		try {
			log.info("Processing mail job: kind={}, to={}, subject={}", request.kind(), request.to(), request.subject());
			mailSenderClient.send(
					request.to(),
					request.subject(),
					request.textBody(),
					request.htmlBody()
			);
			log.debug("Mail sent: to={}, kind={}, subject={}", request.to(), request.kind(), request.subject());
		} catch (Exception e) {
			log.error("Failed to process mail job: kind={}, to={}, subject={}, err={}", request.kind(), request.to(), request.subject(), e.toString());
			// Gerekirse burada DLQ/retry/alert eklenebilir.
		}
	}
}