package com.berkayb.soundconnect.modules.media.config;

import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;



 // ----------------------------------Terimlerde Takilirsan RabbitMQ.md'ye bak------------------------------------------

/**
 * video transcode sistemi icin RabbitMQ ayarlari
 * 2 farkli exchange (biri normal, biri DLX: hata mesajlari icin)
 * 2 kuyruk (biri ana isleri, biri DLQ yani hatalilari toplar. )
 * kuyruklar ve exhange'ler belirli anahtarla birbirine baglaniyooorr
 */
@Configuration
@Profile("!test")
public class MediaTranscodeRabbitConfig {
	
	@Value("${rabbitmq.media.exchange}") // normal mesajlarin exchange adi
	private String mediaExchangeName;
	
	@Value("${rabbitmq.media.dlx}") // DLX: hatali mesajlarin exchange adi
	private String mediaDlxName;
	
	@Value("${rabbitmq.media.queues.videoHls}") // ana kuyrugun adi
	private String videoHlsQueueName;
	
	@Value("${rabbitmq.media.queues.videoHlsDlq}") // hatali mesaj kuyrugu
	private String videoHlsDlqName;
	
	@Value("${rabbitmq.media.routingKeys.videoHlsRequest}") // Ana routing key
	private String videoHlsRoutingKey;
	
	@Value("${rabbitmq.media.routingKeys.videoHlsRequestDlq}") // DLQ routing key
	private String videoHlsRoutingKeyDlq;
	
	// ------EXCHANGE TANIMLARI-------
	
	@Bean(name = "mediaTranscodeExchange")
	public TopicExchange mediaTranscodeExchange() {
		// normal mesajlar icin topic exchane (kalici)
		return ExchangeBuilder.topicExchange(mediaExchangeName)
				.durable(true)
				.build();
	}
	
	@Bean(name = "mediaTranscodeDlx")
	public TopicExchange mediaTranscodeDlx() {
		// dead-letter exchange (basarisiz mesajlar buraya publish edilir)
		return ExchangeBuilder.topicExchange(mediaDlxName)
				.durable(true)
				.build();
	}
	
	// ----Kuyruklar-----
	
	@Bean(name = "videoHlsQueue")
	public Queue videoHlsQueue() {
		// ana is kuyrugu mesajlar burada islenir
		// islenemeyen mesajlar otomatik olarak DLX'e gider
		return QueueBuilder.durable(videoHlsQueueName)
				.withArgument("x-dead-letter-exchange",mediaDlxName) // hatali mesajlari DLX'e yolla
				.withArgument("x-dead-letter-routing-key", videoHlsRoutingKeyDlq) // DLX'e giderken hangi anahtari kullansin
				.build();
	}
	
	@Bean(name = "videoHlsDlq")
	public Queue videoHlsDlq() {
		// islenemeyen hatali mesajlar bu kuyruga duser
		return QueueBuilder.durable(videoHlsDlqName)
				.build();
	}
	
	// ----Binding'ler------
	
	// @Qualifier anatasyonu: ayni tipte birden fazla bean varsa spring ablanin kafasi karismamasi icin hangisini kastettigimizi belirtiyoruz
	// aksi halde NoUniqueBeanDefinitionException yiyebiliriz hic de afiyet olmaz :D
	
	@Bean
	public Binding bindVideoHlsQueue(@Qualifier("videoHlsQueue") Queue videoHlsQueue, @Qualifier("mediaTranscodeExchange") TopicExchange mediaTranscodeExchange) {
		// Ana queue ile exchange'i bagla, gelen dogru anahtarlarla mesajlari ana kuyruga yonlendir
		return BindingBuilder.bind(videoHlsQueue).to(mediaTranscodeExchange).with(videoHlsRoutingKey);
	}
	
	@Bean
	public Binding bindVideoHlsDlq(@Qualifier("videoHlsDlq") Queue videoHlsDlq,@Qualifier("mediaTranscodeDlx") TopicExchange mediaTranscodeDlx) {
		 {
			// DLQ ile DLX'i bagla ve hatalai mesajlari DLQ kuyruguna yonlendir
			return BindingBuilder.bind(videoHlsDlq).to(mediaTranscodeDlx).with(videoHlsRoutingKeyDlq);
		}
	}
}