package com.berkayb.soundconnect.modules.media.deletion;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaDeletionProperties.class)
public class MediaDeletionConfiguration {

	@Bean(name = "mediaDeletionExecutor")
	ThreadPoolTaskExecutor mediaDeletionExecutor(MediaDeletionProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getWorkerThreads());
		executor.setMaxPoolSize(properties.getWorkerThreads());
		executor.setQueueCapacity(properties.getQueueCapacity());
		executor.setThreadNamePrefix("media-deletion-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}
}
