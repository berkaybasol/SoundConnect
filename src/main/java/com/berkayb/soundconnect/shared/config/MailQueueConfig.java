package com.berkayb.soundconnect.shared.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// ------------------- DİKKAT: TERİMLERDE TAKILIRSAN 'DOCS/RABBITMQ.MD' DOSYASINA GİT! -------------------

/**
 * RabbitMQ'da e-mail dogrulama mesajlari icin
 * - Direct Exhange
 * - Queue
 * - Binding
 * tanimlamalarini yapan konfigurasyon sinifidir.
 */

@Configuration
public class MailQueueConfig {
	public static final String EMAIL_VERIFICATION_QUEUE = "email-verification-queue";
	public static final String EMAIL_VERIFICATION_EXCHANGE = "email-verification-exchange";
	public static final String EMAIL_VERIFICATION_ROUTINGKEY = "email.verification";
	
	// Queue tanimlayan method (kuyruk yoksa otomatik olusturulur)
	// Queue: Gonderilen mesajlarin siralandigi yer.
	@Bean
	public Queue emailVerificationQueue() {
		// Durable = true: sunucu restart olsa bile mesajlar silinmez.
		return new Queue(EMAIL_VERIFICATION_QUEUE, true);
	}
	
	// Direct Exchange tanimlayan method (dogrudan eslesen routing key'lere mesaj iletir.)
	// Direct Exchange: mesajlarin routing key ile tam eslestigi kuyruga gitmesini saglar.
	@Bean
	public DirectExchange emailVerificationExchange() {
		// Durable = true restart sonrasi exchange kaybolmaz: autoDelete: false (manuel silinmedikce kalir)
		return new DirectExchange(EMAIL_VERIFICATION_EXCHANGE, true, false);
	}
	
	// Bindingi tanimlayan method,
	// Exchange'den routing key ile kuyruga mesaj yonlendirir.
	@Bean
	public Binding emailVerificationBinding(Queue emailVerificationQueue, DirectExchange emailVerificationExchange) {
		// Exchange ve queue'yu routing key'e gore baglar
		// .bind: bu binding hangi queue'ya uygulanacak?
		// .to: hangi exchange'den mesaj gelecek?
		// .with: hangi routing key ile bu queue'ya mesaj dusecek?
		return BindingBuilder
				.bind(emailVerificationQueue) // mesaji bu queu'ya bagla
				.to(emailVerificationExchange) // mesajlar bu exchange'den gelecek
				.with(EMAIL_VERIFICATION_ROUTINGKEY); // yalnizca bu routing key'e sahip mesajlar bu kuyruga gelsin.
	}
}