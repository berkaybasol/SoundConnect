package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TableGroupNotificationOutboxProperties.class)
public class TableGroupNotificationOutboxConfiguration {
	public static final String EXECUTOR_BEAN = "tableGroupNotificationOutboxExecutor";

	@Bean(name = EXECUTOR_BEAN)
	public Executor tableGroupNotificationOutboxExecutor(TableGroupNotificationOutboxProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getWorkerThreads());
		executor.setMaxPoolSize(properties.getWorkerThreads());
		executor.setQueueCapacity(properties.getQueueCapacity());
		executor.setThreadNamePrefix("table-group-notification-outbox-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(10);
		return executor;
	}
}
