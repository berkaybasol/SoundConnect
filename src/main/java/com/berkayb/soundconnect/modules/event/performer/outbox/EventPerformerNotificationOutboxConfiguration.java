package com.berkayb.soundconnect.modules.event.performer.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EventPerformerNotificationOutboxProperties.class)
public class EventPerformerNotificationOutboxConfiguration {
	public static final String EXECUTOR_BEAN = "eventPerformerNotificationOutboxExecutor";

	@Bean(name = EXECUTOR_BEAN)
	public Executor eventPerformerNotificationOutboxExecutor(
			EventPerformerNotificationOutboxProperties properties
	) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getWorkerThreads());
		executor.setMaxPoolSize(properties.getWorkerThreads());
		executor.setQueueCapacity(properties.getQueueCapacity());
		executor.setThreadNamePrefix("event-performer-notification-outbox-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		return executor;
	}
}
