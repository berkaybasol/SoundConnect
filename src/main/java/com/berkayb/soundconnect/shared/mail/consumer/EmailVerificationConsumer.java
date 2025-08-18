package com.berkayb.soundconnect.shared.mail.consumer;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.dto.EmailVerificationMessage;
import com.berkayb.soundconnect.shared.mail.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.berkayb.soundconnect.shared.config.MailQueueConfig.EMAIL_VERIFICATION_QUEUE;

// ------------------- DİKKAT: TERİMLERDE TAKILIRSAN 'DOCS/RABBITMQ.MD' DOSYASINA GİT! -------------------


/**
 * Email dogrulama mesajlarini RabbitMQ kuyrugundan cekip isleyen Consumer sinifidir.
 * Gelen mesaji ilgili MailService'e yonlendirir.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationConsumer {
	
	private final MailService mailService;
	
	// Kuyruktan mesajı dinler, DTO'dan mesaj gelirse bu method tetiklenir.
	@RabbitListener(queues = EMAIL_VERIFICATION_QUEUE) // dinlenilen kuyruk
	public void listenVerificationMail(EmailVerificationMessage message) {
		try{
			log.info("Received verification code mail request for email={} (code={})", message.email(), message.code());
			// dogrulama mailini gonderiyoruz.
			mailService.sendVerificationMail(message.email(), message.code());
			log.info("Verification mail sent to email={}", message.email());
		} catch (Exception e){
			log.error("Failed to process verification code mail for email={} (code={})", message.email(), message.code(), e);
			throw new SoundConnectException(ErrorType.MAIL_QUEUE_ERROR,
			                                List.of("Mail adresi: " + message.email(),
			                                        "Token: " + message.code(),
			                                        "Hata: " + e.getMessage()));
		}
	}
}