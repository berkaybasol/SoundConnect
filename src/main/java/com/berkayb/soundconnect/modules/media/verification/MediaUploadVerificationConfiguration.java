package com.berkayb.soundconnect.modules.media.verification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaUploadVerificationProperties.class)
public class MediaUploadVerificationConfiguration {

	@Bean(name = "mediaUploadVerificationExecutor")
	ThreadPoolTaskExecutor mediaUploadVerificationExecutor(
			MediaUploadVerificationProperties properties
	) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getWorkerThreads());
		executor.setMaxPoolSize(properties.getWorkerThreads());
		executor.setQueueCapacity(properties.getQueueCapacity());
		executor.setThreadNamePrefix("media-upload-verify-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}
}
