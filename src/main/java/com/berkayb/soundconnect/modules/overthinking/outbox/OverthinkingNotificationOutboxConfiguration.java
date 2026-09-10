package com.berkayb.soundconnect.modules.overthinking.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OverthinkingNotificationOutboxProperties.class)
public class OverthinkingNotificationOutboxConfiguration {
	public static final String EXECUTOR_BEAN = "overthinkingNotificationOutboxExecutor";

	@Bean(name = EXECUTOR_BEAN)
	public Executor overthinkingNotificationOutboxExecutor(
			OverthinkingNotificationOutboxProperties properties
	) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getWorkerThreads());
		executor.setMaxPoolSize(properties.getWorkerThreads());
		executor.setQueueCapacity(properties.getQueueCapacity());
		executor.setThreadNamePrefix("overthinking-notification-outbox-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		return executor;
	}
}
