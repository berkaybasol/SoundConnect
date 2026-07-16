package com.berkayb.soundconnect.modules.media.image;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnImageVariantWorker
public class ImageVariantWorkerConfiguration {

	@Bean(name = "imageVariantExecutor")
	ThreadPoolTaskExecutor imageVariantExecutor(MediaImageVariantProperties properties) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(properties.getBackfill().getWorkerThreads());
		executor.setMaxPoolSize(properties.getBackfill().getWorkerThreads());
		executor.setQueueCapacity(properties.getBackfill().getQueueCapacity());
		executor.setThreadNamePrefix("media-image-variant-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}
}
