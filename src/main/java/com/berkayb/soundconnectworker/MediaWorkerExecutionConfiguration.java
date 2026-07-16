package com.berkayb.soundconnectworker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Native-worker-only executors; API dispatch/cleanup pools are intentionally absent. */
@Configuration(proxyBeanMethods = false)
public class MediaWorkerExecutionConfiguration {

	@Bean(name = "mediaTranscodeLeaseScheduler")
	ThreadPoolTaskScheduler mediaTranscodeLeaseScheduler(
			@Value("${media.transcode.lease.heartbeat-threads:2}") int heartbeatThreads
	) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(Math.max(1, Math.min(heartbeatThreads, 4)));
		scheduler.setThreadNamePrefix("media-worker-lease-");
		scheduler.setRemoveOnCancelPolicy(true);
		scheduler.setWaitForTasksToCompleteOnShutdown(false);
		return scheduler;
	}

	@Bean(name = "mediaHlsWorkerTaskExecutor")
	ThreadPoolTaskExecutor mediaHlsWorkerTaskExecutor(
			@Value("${media.transcode.worker-threads:1}") int workerThreads
	) {
		if (workerThreads != 1) {
			throw new IllegalStateException(
					"media.transcode.worker-threads must be 1 for the isolated temp-volume budget");
		}
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(1);
		executor.setThreadNamePrefix("media-worker-hls-");
		executor.setWaitForTasksToCompleteOnShutdown(false);
		return executor;
	}
}
