package com.berkayb.soundconnect.modules.media.abuse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
		MediaUploadGuardProperties.class,
		MediaUploadCleanupProperties.class
})
public class MediaUploadAbuseConfiguration {

	@Bean(name = "mediaUploadCleanupExecutor")
	ThreadPoolTaskExecutor mediaUploadCleanupExecutor(MediaUploadCleanupProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getWorkerThreads());
		executor.setMaxPoolSize(properties.getWorkerThreads());
		executor.setQueueCapacity(properties.getQueueCapacity());
		executor.setThreadNamePrefix("media-upload-cleanup-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}
}
