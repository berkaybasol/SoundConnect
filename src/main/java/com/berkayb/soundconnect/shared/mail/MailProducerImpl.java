package com.berkayb.soundconnect.shared.mail;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.dto.EmailVerificationMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.berkayb.soundconnect.shared.config.MailQueueConfig.*;


     // ------------------- DİKKAT: TERİMLERDE TAKILIRSAN 'DOCS/RABBITMQ.MD' DOSYASINA GİT! -------------------


// Email dogrulama mesajini RabbitMQ'ya atan Producer servisidir.


@Service
@RequiredArgsConstructor
@Slf4j
public class MailProducerImpl implements MailProducer {
	private final RabbitTemplate rabbitTemplate; // Spring Boot'un mesaj gonderme arayuzu
	
	@Override
	public void sendVerificationMail(String email, String verificationToken) {
		// DTO'dan nesne uret.
		EmailVerificationMessage message = new EmailVerificationMessage(email, verificationToken);
		
		
		/**
		 * Dis servislerden, frameworklerden alinan methodlar mutlaka try-catch'e sarilmalidir cunku
		 * bu cagrilar dis sistemlere baglidir. ag hatasi, zaman asimi, yetki sorunu gibi beklenmeyen durumlar
		 * olusabilir.
		 */
		try {
			// mesaji exhange + routing key ile kuyruga gonder
			// (convertAndSend: exchange, routinKey, payload)
			rabbitTemplate.convertAndSend(EMAIL_VERIFICATION_EXCHANGE, // Hangi exchange'e gidecek
			                              EMAIL_VERIFICATION_ROUTINGKEY, // Hangi routing key ile yonlendirilecek
			                              message // queue'ye gidecek mesaj. burasi convertAndSend. in payload kismi. mesaj burada otomatik
			                              // olarak Json cevriliyor
			);
			log.info("Verification mail queued for email={} (token={})", email, verificationToken);
		} catch (Exception e) {
			log.error("Failed to queue verification mail for email={} (token={})", email, verificationToken, e);
			throw new SoundConnectException(ErrorType.MAIL_QUEUE_ERROR,
			                                List.of("Mail adresi: " + email, "Token: " + verificationToken, "Hata: " + e.getMessage()));
		}
		// NOT: JSON'a cevirme islemini config dosyasinda tanimladigimiz Jackson2JsonMessageConverter otomatik yapiyor.
		// boylelikle consumer tarafinda otomatik olarak EmailVerificationMessage dto'su ile alinabiliyor.
	}
}