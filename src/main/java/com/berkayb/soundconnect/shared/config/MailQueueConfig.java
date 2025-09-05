package com.berkayb.soundconnect.shared.config;


import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

    // ------------------- DİKKAT: TERİMLERDE TAKILIRSAN 'DOCS/RABBITMQ.MD' DOSYASINA GİT! -------------------

/**
 * Mail ve DLQ kuyrugu icin RabbitMQ konfigurasyon sinifi.
 * Amac:
 * - Maillerin asenkron, guvenli sekilde kuyruga alinmasini saglamak.
 * - Hatali isler otomatik olarak DLQ (dead-letter-queue) kuyrugunda biriksin.
 * - Boylece hem monitoring, hem retry, hem de olasi hata yonetimi yapilabilir.
 */

@Configuration
public class MailQueueConfig {
	// Main mail queue/exchange/routing key sabitleri
	public static final String MAIL_QUEUE = "mail-queue";
	public static final String MAIL_EXCHANGE = "mail-exchange";
	public static final String MAIL_ROUTING_KEY = "mail.send";
	
	// DLQ sabitleri
	public static final String MAIL_DLQ = "mail-queue-dlq";
	public static final String MAIL_DLQ_EXCHANGE = "mail-queue-dlq-exchange";
	
	/**
	 * Main mail kuygrugunu olusturur.
	 * x-dead-letter-exchange argumanlari ile DLQ'ya baglanir.
	 */
	@Bean
	public Queue mailQueue() {
		return QueueBuilder.durable(MAIL_QUEUE) // kuyrugu restar'tan etkilenmeyecek sekilde olustur.
				.withArgument("x-dead-letter-exchange", MAIL_DLQ_EXCHANGE) // hatali mesaji DLQ exchange'e yonlendir.
				.withArgument("x-dead-letter-routing-key", MAIL_DLQ) // DLQ exchange'de hangi queue'ya dusmesini belirleyen routingKey
				.build();
	}
	
	// Direct Exchange tanimlayan method (dogrudan eslesen routing key'lere mesaj iletir.)
	// Direct Exchange: mesajlarin routing key ile tam eslestigi kuyruga gitmesini saglar.
	@Bean
	public DirectExchange mailExchange() {
		return new DirectExchange(MAIL_EXCHANGE, true, false);
	}
	
	
	// Bindingi tanimlayan method,
	// Exchange'den routing key ile kuyruga mesaj yonlendirir.
	@Bean
	public Binding mailBinding(Queue mailQueue, DirectExchange mailExchange) {
		// Exchange ve queue'yu routing key'e gore baglar
		// .bind: bu binding hangi queue'ya uygulanacak?
		// .to: hangi exchange'den mesaj gelecek?
		// .with: hangi routing key ile bu queue'ya mesaj dusecek?
		return BindingBuilder
				.bind(mailQueue) // mesaji bu queu'ya bagla
				.to(mailExchange) // mesajlar bu exchange'den gelecek
				.with(MAIL_ROUTING_KEY); // yalnizca bu routing key'e sahip mesajlar bu kuyruga gelsin.
	}
	
	// DLQ kuyrugu
	// Hatali isler burada birikir
	@Bean
	public Queue mailDlqQueue() {
		return new Queue(MAIL_DLQ, true); // Durable: restart sonrası kaybolmaz
	}
	
	// DLQ icin exchange.
	@Bean
	public DirectExchange mailDlqExchange() {
		return new DirectExchange(MAIL_DLQ_EXCHANGE, true, false);
	}
	
	// DLQ kuyrugu ile exchange arasinda baglanti (bind)
	@Bean
	public Binding mailDlqBinding(Queue mailDlqQueue, DirectExchange mailDlqExchange) {
		return BindingBuilder
				.bind(mailDlqQueue)
				.to(mailDlqExchange)
				.with(MAIL_DLQ);
	}
}