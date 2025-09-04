package com.berkayb.soundconnect.shared.config;

// RabbitMQ'da Queue ve Spring'in Bean yönetimi için gerekli importlar
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
	public static final String MAIL_QUEUE = "mail-queue";
	public static final String MAIL_EXCHANGE = "mail-exchange";
	public static final String MAIL_ROUTING_KEY = "mail.send";
	
	// Queue tanimlayan method (kuyruk yoksa otomatik olusturulur)
	// Queue: Gonderilen mesajlarin siralandigi yer.
	@Bean
	public Queue mailQueue() {
		// Durable: true -> restart sonrası queue kaybolmaz
		return new Queue(MAIL_QUEUE, true);
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
}