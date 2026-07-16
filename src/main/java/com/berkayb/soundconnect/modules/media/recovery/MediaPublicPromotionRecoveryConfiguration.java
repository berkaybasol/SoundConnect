package com.berkayb.soundconnect.modules.media.recovery;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MediaPublicPromotionRecoveryProperties.class)
public class MediaPublicPromotionRecoveryConfiguration {

	@Bean(name = "mediaPublicPromotionRecoveryExecutor")
	ThreadPoolTaskExecutor mediaPublicPromotionRecoveryExecutor(
			MediaPublicPromotionRecoveryProperties properties
	) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getWorkerThreads());
		executor.setMaxPoolSize(properties.getWorkerThreads());
		executor.setQueueCapacity(properties.getQueueCapacity());
		executor.setThreadNamePrefix("media-public-recovery-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}
}
