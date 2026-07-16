package com.berkayb.soundconnectworker;

import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitConnectionFactoryBean;
import org.springframework.boot.autoconfigure.amqp.CachingConnectionFactoryConfigurer;
import org.springframework.boot.autoconfigure.amqp.RabbitConnectionFactoryBeanConfigurer;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import javax.sql.DataSource;

/**
 * Consumer-only Rabbit connection. Excluding RabbitAutoConfiguration keeps
 * RabbitTemplate and RabbitAdmin (publish/topology mutation capabilities) out
 * of the native worker context while retaining Boot's TLS/property handling.
 */
@Configuration(proxyBeanMethods = false)
@EnableRabbit
@EnableConfigurationProperties(RabbitProperties.class)
public class MediaWorkerRabbitConnectionConfiguration {

	@Bean(destroyMethod = "destroy")
	ConnectionFactory mediaWorkerRabbitConnectionFactory(
			RabbitProperties properties,
			ResourceLoader resourceLoader
	) throws Exception {
		RabbitConnectionFactoryBean nativeFactoryBean = new RabbitConnectionFactoryBean();
		new RabbitConnectionFactoryBeanConfigurer(resourceLoader, properties)
				.configure(nativeFactoryBean);
		nativeFactoryBean.afterPropertiesSet();

		CachingConnectionFactory connectionFactory =
				new CachingConnectionFactory(nativeFactoryBean.getObject());
		new CachingConnectionFactoryConfigurer(properties)
				.configure(connectionFactory, properties);
		return connectionFactory;
	}

	@Bean
	MediaWorkerDependencyHealthIndicator mediaWorkerDependencyHealthIndicator(
			DataSource dataSource,
			ConnectionFactory connectionFactory
	) {
		return new MediaWorkerDependencyHealthIndicator(dataSource, connectionFactory);
	}
}
