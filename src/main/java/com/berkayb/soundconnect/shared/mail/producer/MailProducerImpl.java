package com.berkayb.soundconnect.shared.mail.producer;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;


// ------------------- DİKKAT: TERİMLERDE TAKILIRSAN 'DOCS/RABBITMQ.MD' DOSYASINA GİT! -------------------

/**
 * Mail Job'lari RabbitMQ'ya publish eder
 * Akis:
 * - MailSendRequest nesnesi exchange + routingKey ile publish edilir
 * - Her mesaj icin benzersiz correlationId uretilir
 * - Broked'dan ACK/NACK cevabi beklenir
 * - Eger NACK gelirse SoundConnectException firlatilir
 * - Eger mesaj route edilemezse ReturnedMessage ile hata yakalanir.
 * - Basariyla publish olursa log'a PII maskeli bilgi yazilir.
 *
 * Amac:
 * - Mail job'lari RabitMQ'ya publish eder ve broker confirm/retrun ile dogrular
 */

@Service
@RequiredArgsConstructor
@Slf4j
public class MailProducerImpl implements MailProducer {
	private final RabbitTemplate rabbitTemplate; // Spring Boot'un mesaj gonderme arayuzu
	private final MailJobHelper helper;
	
	@Value("${mail.exchange:mail.exchange}")
	private String mailExchange;
	
	@Value("${mail.routingKey:mail.send}")
	private String mailRoutingKey;
	
	// Broker confirm/retrun icin maks bekleme suresi
	@Value("${mail.producer.confirmTimeoutSec:5}")
	private long confirmTimeoutSec;
	
	@Override
	public void send(MailSendRequest request) {
		final String correlationId = UUID.randomUUID().toString();
		final CorrelationData cd = new CorrelationData(correlationId);
		try {
			
			// mesaji publish et
			rabbitTemplate.convertAndSend(mailExchange, mailRoutingKey, request, cd);
			
			// confirm/ return bekle ve hatayi yuzeye cikar
			CorrelationData.Confirm confirm =
					cd.getFuture().get(confirmTimeoutSec, TimeUnit.SECONDS);
			
			// NACK ise domain hatasi firlat
			if (!confirm.isAck()) {
				throw new SoundConnectException(ErrorType.MAIL_QUEUE_ERROR, List.of("Broker NACK",
				                                                                    "reason=" + confirm.getReason(),
				                                                                    "exchange=" + mailExchange,
				                                                                    "routingKey=" + mailRoutingKey,
				                                                                    "correlationId=" + correlationId)
				);
			}
			
			// Return (route edilemedi kontrolu)
			ReturnedMessage returned = cd.getReturned();
			if (returned != null) {
				throw new SoundConnectException(
						ErrorType.MAIL_QUEUE_ERROR,
						List.of("Message RETURNED",
						        "replyCode=" +returned.getReplyCode(),
						        "replyText=" + returned.getReplyText(),
						        "exchange=" + returned.getExchange(),
						        "routingKey=" + returned.getRoutingKey(),
						        "correlationId=" + correlationId)
				);
			}
			// publish basarili
			log.info("Mail job queued: kind={}, to={}, subject={}",
			         request.kind(), helper.maskEmail(request.to()), request.subject());
			
		} catch (SoundConnectException e) {
			// domain hatasi aynen firlat
			throw e;
		} catch (Exception e) {
			// timeout vs. genel publish hatalari
			throw new SoundConnectException(
					ErrorType.MAIL_QUEUE_ERROR,
					List.of("Publish failed",
					        "exchange=" + mailExchange,
					        "routingKey=" + mailRoutingKey,
							"correlationId=" + correlationId,
					        "error=" + e.getMessage())
			);
		}
	}
}