package com.berkayb.soundconnect.modules.media.abuse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Keeps READY-row recovery independent from stale-upload cleanup backlogs. */
@Configuration(proxyBeanMethods = false)
public class MediaProtectedUploadRecoveryConfiguration {

	@Bean(name = "mediaProtectedUploadRecoveryExecutor")
	ThreadPoolTaskExecutor mediaProtectedUploadRecoveryExecutor(
			MediaUploadCleanupProperties properties
	) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getProtectedMutableRecoveryWorkerThreads());
		executor.setMaxPoolSize(properties.getProtectedMutableRecoveryWorkerThreads());
		executor.setQueueCapacity(properties.getProtectedMutableRecoveryQueueCapacity());
		executor.setThreadNamePrefix("media-protected-recovery-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}
}
