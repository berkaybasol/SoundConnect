package com.berkayb.soundconnect.modules.media.transcode;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Long-running FFmpeg work must never use Spring Rabbit's high general-purpose
 * prefetch. One unacked delivery per consumer keeps work fair across instances
 * and prevents restart redelivery storms. MANUAL acknowledgement happens after
 * the durable database lease claim commits and before multi-hour native work.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
		prefix = "media.transcode.worker",
		name = "enabled",
		havingValue = "true",
		matchIfMissing = false
)
public class MediaRabbitListenerConfiguration {

	@Bean(name = "mediaVideoHlsListenerFactory")
	SimpleRabbitListenerContainerFactory mediaVideoHlsListenerFactory(
			ConnectionFactory connectionFactory,
			@Value("${spring.rabbitmq.listener.simple.auto-startup:true}") boolean autoStartup
	) {
		SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
		factory.setConnectionFactory(connectionFactory);
		factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
		factory.setPrefetchCount(1);
		factory.setDefaultRequeueRejected(false);
		factory.setAutoStartup(autoStartup);
		return factory;
	}
}
