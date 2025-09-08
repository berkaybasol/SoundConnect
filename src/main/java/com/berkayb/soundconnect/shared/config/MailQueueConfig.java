package com.berkayb.soundconnect.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.CustomExchange;

import java.util.Map;

// ------------------- DİKKAT: TERİMLERDE TAKILIRSAN 'DOCS/RABBITMQ.MD' DOSYASINA GİT! -------------------

/**
 * Mail ve DLQ kuyrugu icin RabbitMQ konfigurasyon sinifi.
 * Amac:
 * - Maillerin asenkron, guvenli sekilde kuyruga alinmasini saglamak.
 * - Hatali isler otomatik olarak DLQ (dead-letter-queue) kuyrugunda biriksin.
 * - Boylece hem monitoring, hem retry, hem de olasi hata yonetimi yapilabilir.
 */

@Configuration
@EnableRabbit
@Slf4j
public class MailQueueConfig {
	
	
	// DEFAULT sabitler (herhangi bir nedenden dolayi yml okunmazsa burasi devreye girsin)
	public static final String MAIL_QUEUE_DEFAULT = "mail.queue";
	public static final String MAIL_EXCHANGE_DEFAULT = "mail.exchange";
	public static final String MAIL_ROUTING_KEY_DEFAULT = "mail.send";
	public static final String MAIL_DLQ_DEFAULT = "mail.queue.dlq";
	public static final String MAIL_DLQ_EXCHANGE_DEFAULT = "mail.dlx";
	
	@Value("${mail.queueName:" + MAIL_QUEUE_DEFAULT + "}")
	private String mailQueueName;
	
	@Value("${mail.exchange:" + MAIL_EXCHANGE_DEFAULT + "}")
	private String mailExchangeName;
	
	@Value("${mail.routingKey:" + MAIL_ROUTING_KEY_DEFAULT + "}")
	private String mailRoutingKey;
	
	@Value("${mail.dlq:" + MAIL_DLQ_DEFAULT + "}")
	private String mailDlqName;
	
	@Value("${mail.dlx:" + MAIL_DLQ_EXCHANGE_DEFAULT + "}")
	private String mailDlxExchangeName;
	
	@Value("${mail.ttlMs:120000}")
	private long mailMessageTtlMs;
	
	
	/**
	 * RabbitMQ consumer'larinin ayni anda kac thread ile calisacagini belirler
	 * Format: "min-max" Orn : "1-5" -> en az 1 en fazla 5 paralel thread calisabilir.
	 * Spring bu degeri parse ederek dinleyici havuzunu dinamik sekilde ayarlar.
	 * performans tuning icin kritik: yogunluk arttikca thread sayisi da artabilir.
	 */
	@Value("${mail.listener.concurrency:1-5}")
	private String listenerConcurrency; // "min-max" formati
	
	
	/**
	 * Prefetch: Her Consumer'in ayni anda kac mesaji onceden alabilecegini belirler.
	 * Orn: prefetch=10 -> Conmsumer sirayla islemek uzere 10 mesaji onceden ceker.
	 * Bu ayar mesaj isleme verimliligini ve memory kullanimini dogrudan etkiler.
	 * dusuk prefetch daha kontrollu isler yuksek prefetch daha hizli
	 */
	@Value("${mail.listener.prefetch:10}")
	private int listenerPrefetch; // her consumer'in ayni anda kac mesaji onceden alabilecegini belirliyoruz prefetch = onceden cekilecek mesaj sayisi
	
	// ------------------ EXCHANGE / QUEUE / BINDINGS ------------------
	
	// Main mail queue'u olusturur (DLX + TTL bagli)
	@Bean
	public Queue mailQueue() {
		return QueueBuilder.durable(mailQueueName) // kuyrugu restart'tan etkilenmeyecek sekilde olustur.
				.withArgument("x-dead-letter-exchange", mailDlxExchangeName) // hatali mesaji DLQ exchange'e yonlendir.
				.withArgument("x-dead-letter-routing-key", mailDlqName) // DLQ exchange'de hangi queue'ya dusmesini belirleyen routingKey
				.withArgument("x-message-ttl", mailMessageTtlMs) // time to live
				.build();
	}
	
	// Main mail exhange( Direct - Routing key ile tam eslesme)
	@Bean
	public DirectExchange mailExchange() {
		return new DirectExchange(mailExchangeName, true, false);
	}
	
	
	// Main binding: exchange -> queue (routing key ile)
	@Bean
	public Binding mailBinding(Queue mailQueue, DirectExchange mailExchange) {
		// Exchange ve queue'yu routing key'e gore baglar
		// .bind: bu binding hangi queue'ya uygulanacak?
		// .to: hangi exchange'den mesaj gelecek?
		// .with: hangi routing key ile bu queue'ya mesaj dusecek?
		return BindingBuilder
				.bind(mailQueue) // mesaji bu queu'ya bagla
				.to(mailExchange) // mesajlar bu exchange'den gelecek
				.with(mailRoutingKey); // yalnizca bu routing key'e sahip mesajlar bu kuyruga gelsin.
	}
	
	// DLQ queue (dead-letter hedefi)
	@Bean
	public Queue mailDlqQueue() {
		return new Queue(mailDlqName, true); // Durable: restart sonrası kaybolmaz
	}
	
	// DLQ exchange
	@Bean
	public DirectExchange mailDlqExchange() {
		return new DirectExchange(mailDlxExchangeName, true, false);
	}
	
	// DLQ binding: DLX -> DLQ (ayni isimli routing key)
	@Bean
	public Binding mailDlqBinding(Queue mailDlqQueue, DirectExchange mailDlqExchange) {
		return BindingBuilder
				.bind(mailDlqQueue)
				.to(mailDlqExchange)
				.with(mailDlqName);
	}
	
	// ------------------ MODULE-SPECIFIC LISTENER FACTORY ------------------
	// AMAC: bu module ozel bir listner factory tanimlamak.
	// boylece farkli moduller kendi concurrency ve prefetch ayarlarini kullanabilir.
	
	// JSON converter ve connection factory RabbitMqConfig'den gelir (component scanning calisiyomus arkada oradan RabbitMqConfig' sinifimi goruyomus ilginc :D)
	@Bean(name = "mailListenerFactory")
	public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(CachingConnectionFactory connectionFactory, Jackson2JsonMessageConverter jackson2JsonMessageConverter ) {
		SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
		
		// RabbitMQ baglantisi ve mesaj formati tanimliyoruz
		f.setConnectionFactory(connectionFactory);
		f.setMessageConverter(jackson2JsonMessageConverter);
		
		// MANUAL: consumer mesaji isledikten sonra elle onay verir boylece hata durumunda retry veya DLQ yonlendirmesi yapilabilir.
		f.setAcknowledgeMode(AcknowledgeMode.MANUAL);
		
		// yukarda acikladik
		f.setPrefetchCount(listenerPrefetch);
		
		// 1-5 formatini parse et
		int min = 1, max = 5;
		try {
			String[] parts = listenerConcurrency.split("-");
			min = Integer.parseInt(parts[0].trim());
			max = (parts.length > 1) ? Integer.parseInt(parts[1].trim()) : min;
		} catch (Exception e) {
			log.warn("mail.listener.concurrency='{}' parse edilemedi, defaults kullanılacak.", listenerConcurrency);
		}
		
		f.setConcurrentConsumers(min);
		f.setMaxConcurrentConsumers(max);
		return f;
	}
	
	@Bean
	public CustomExchange mailDelayedExchange() {
		// x-delayed-message, internal type: direct
		return new CustomExchange(
				"mail.delayed",
				"x-delayed-message",
				true,
				false,
				Map.of("x-delayed-type", "direct")
		);
	}
	
	// Delayed exchange -> main mail queue (ayni routing key)
	@Bean
	public Binding mailDelayedBinding(Queue mailQueue, CustomExchange mailDelayedExchange) {
		return BindingBuilder
				.bind(mailQueue)
				.to(mailDelayedExchange)
				.with(mailRoutingKey)   // aynı routing key
				.noargs();
	}
	
	/**
	 * Diger siniflar bu kuyruk isimlerine sabit olarak erisebilsin diye tanimliyoruz
	 * orn: @RabbitListener(queues = MailQueueConfig.MAIL_QUEUE) gibi..
	 */
	public static final String MAIL_QUEUE = MAIL_QUEUE_DEFAULT;
	public static final String MAIL_DLQ   = MAIL_DLQ_DEFAULT;
}