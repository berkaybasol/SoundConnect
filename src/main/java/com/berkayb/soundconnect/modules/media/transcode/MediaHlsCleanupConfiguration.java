package com.berkayb.soundconnect.modules.media.transcode;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class MediaHlsCleanupConfiguration {

	@Bean(name = "mediaTranscodeDispatchExecutor")
	ThreadPoolTaskExecutor mediaTranscodeDispatchExecutor(
			@Value("${media.transcode.dispatch.worker-threads:2}") int workerThreads,
			@Value("${media.transcode.dispatch.queue-capacity:100}") int queueCapacity
	) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		int safeWorkers = Math.max(1, Math.min(workerThreads, 8));
		executor.setCorePoolSize(safeWorkers);
		executor.setMaxPoolSize(safeWorkers);
		executor.setQueueCapacity(Math.max(1, Math.min(queueCapacity, 1000)));
		executor.setThreadNamePrefix("media-transcode-dispatch-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}

	@Bean(name = "mediaHlsCleanupExecutor")
	ThreadPoolTaskExecutor mediaHlsCleanupExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(100);
		executor.setThreadNamePrefix("media-hls-cleanup-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		return executor;
	}

	@Bean(name = "mediaTranscodeLeaseScheduler")
	ThreadPoolTaskScheduler mediaTranscodeLeaseScheduler(
			@Value("${media.transcode.lease.heartbeat-threads:4}") int heartbeatThreads
	) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(Math.max(1, Math.min(heartbeatThreads, 16)));
		scheduler.setThreadNamePrefix("media-transcode-lease-");
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
					"media.transcode.worker-threads must remain 1 until a shared weighted temp-disk budget is configured");
		}
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		// The admission semaphore still allows one accepted task. A single queue
		// slot closes the tiny handoff race where the prior runnable releases its
		// permit in finally just before the pool thread becomes idle.
		executor.setQueueCapacity(1);
		executor.setThreadNamePrefix("media-hls-worker-");
		executor.setWaitForTasksToCompleteOnShutdown(false);
		return executor;
	}
}
