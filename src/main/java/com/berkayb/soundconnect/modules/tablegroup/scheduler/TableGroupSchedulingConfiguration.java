package com.berkayb.soundconnect.modules.tablegroup.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
public class TableGroupSchedulingConfiguration {

	public static final String DEADLINE_SCHEDULER = "tableGroupGameDeadlineTaskScheduler";
	public static final String CLEANUP_SCHEDULER = "tableGroupCleanupTaskScheduler";
	public static final String OUTBOX_SCHEDULER = "tableGroupOutboxTaskScheduler";

	@Bean(name = DEADLINE_SCHEDULER)
	ThreadPoolTaskScheduler tableGroupGameDeadlineTaskScheduler() {
		return singleThreadScheduler("table-group-game-deadline-");
	}

	@Bean(name = CLEANUP_SCHEDULER)
	ThreadPoolTaskScheduler tableGroupCleanupTaskScheduler() {
		return singleThreadScheduler("table-group-cleanup-");
	}

	@Bean(name = OUTBOX_SCHEDULER)
	ThreadPoolTaskScheduler tableGroupOutboxTaskScheduler() {
		return singleThreadScheduler("table-group-outbox-schedule-");
	}

	private ThreadPoolTaskScheduler singleThreadScheduler(String threadNamePrefix) {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix(threadNamePrefix);
		scheduler.setRemoveOnCancelPolicy(true);
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(30);
		scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
		scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		return scheduler;
	}
}
