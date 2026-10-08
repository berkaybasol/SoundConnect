package com.berkayb.soundconnect.modules.notification.config;

import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationPublisherProperties;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Notification icin RabbitMQ altyapi konfigurasyon sinifi.
 * - Topic exchange (notification.exchange)
 * - Ana queue (notification.queue) + DLX/DLQ
 * - Binding (with routing key)
 * - Jackson JSON converter (eventleri JSON olarak tasimak icin)
 */

@Configuration
@EnableConfigurationProperties(NotificationPublisherProperties.class)
public class NotificationRabbitConfig {
	/** Only notification ingress uses this policy; mail and media keep their own factories. */
	@Bean(name = "notificationListenerFactory")
	public SimpleRabbitListenerContainerFactory notificationListenerFactory(
			SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
		var factory = new SimpleRabbitListenerContainerFactory();
		configurer.configure(factory, connectionFactory);
		factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
		factory.setDefaultRequeueRejected(false);
		factory.setAdviceChain(RetryInterceptorBuilder.stateless()
				.retryOperations(NotificationListenerFailurePolicy.retryTemplate())
				.recoverer(NotificationListenerFailurePolicy::rejectExhausted)
				.build());
		factory.setErrorHandler(NotificationListenerFailurePolicy::rejectSafely);
		return factory;
	}
	
	@Value("${app.messaging.notification.exchange:notification.exchange}")
	private String notificationExchangeName;
	
	@Value("${app.messaging.notification.queue:notification.queue}")
	private String notificationQueueName;
	
	// #: notificaiton ile baslayan tum alt seviyeli routing key'lerle eslesir orn: notification.email.push
	@Value("${app.messaging.notification.routingKey:notification.#}")
	private String notificationRoutingKey;
	
	@Value("${app.messaging.notification.dlxExchange:notification.dlx}")
	private String notificationDlxExchangeName;
	
	@Value("${app.messaging.notification.dlq:notification.queue.dlq}")
	private String notificationDlqName;
	
	// ---------Exchanges---------
	
	@Bean
	public TopicExchange notificationExchange() {
		// durable=true, autoDelete=false
		return ExchangeBuilder.topicExchange(notificationExchangeName).durable(true).build();
	}
	
	@Bean
	public TopicExchange notificationDlxExchange() {
		return ExchangeBuilder.topicExchange(notificationDlxExchangeName).durable(true).build();
	}
	
	// ---------Queues---------
	
	@Bean
	public Queue notificationQueue() {
		// Ana queue: basarisiz (dead-letter) mesajlar icin DLX tanimlanir. islenemeyen mesajlar DLQ'ya yonlendirilir.
		return QueueBuilder.durable(notificationQueueName)
		                   .withArguments(Map.of("x-dead-letter-exchange", notificationDlxExchangeName))
		                   .build();
	}
	
	@Bean
	public Queue notificationDlqQueue() {
		// dead-letter queue
		return QueueBuilder.durable(notificationDlqName).build();
	}
	
	// ---------Bindings---------
	
	@Bean
	public Binding notificationBinding(){
		// notification.exchange -> notification.# patterniyle mesajlari notification.queue'a yonlendirir.
		return BindingBuilder
				.bind(notificationQueue())
				.to(notificationExchange())
				.with(notificationRoutingKey);
	}
	
	@Bean
	public Binding notificationDlqBinding(){
		// notification.dlx -> tum mesajlari (#) ile DLQ'ya aktarir (dead-letter routing)
		return BindingBuilder
				.bind(notificationDlqQueue())
				.to(notificationDlxExchange())
				.with("#");
	}
}
