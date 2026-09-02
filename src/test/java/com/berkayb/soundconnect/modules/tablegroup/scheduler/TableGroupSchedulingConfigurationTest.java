package com.berkayb.soundconnect.modules.tablegroup.scheduler;

import com.berkayb.soundconnect.modules.tablegroup.game.scheduler.TableGroupGameDeadlineScheduler;
import com.berkayb.soundconnect.modules.tablegroup.notification.outbox.TableGroupNotificationOutboxScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

class TableGroupSchedulingConfigurationTest {

	@Test
	void createsThreeIndependentSingleThreadSchedulers() {
		TableGroupSchedulingConfiguration configuration = new TableGroupSchedulingConfiguration();

		ThreadPoolTaskScheduler deadline = configuration.tableGroupGameDeadlineTaskScheduler();
		ThreadPoolTaskScheduler cleanup = configuration.tableGroupCleanupTaskScheduler();
		ThreadPoolTaskScheduler outbox = configuration.tableGroupOutboxTaskScheduler();

		assertThat(deadline.getPoolSize()).isEqualTo(1);
		assertThat(cleanup.getPoolSize()).isEqualTo(1);
		assertThat(outbox.getPoolSize()).isEqualTo(1);
		assertThat(deadline.getThreadNamePrefix()).isEqualTo("table-group-game-deadline-");
		assertThat(cleanup.getThreadNamePrefix()).isEqualTo("table-group-cleanup-");
		assertThat(outbox.getThreadNamePrefix()).isEqualTo("table-group-outbox-schedule-");
		assertThat(deadline.isRemoveOnCancelPolicy()).isTrue();
		assertThat(cleanup.isRemoveOnCancelPolicy()).isTrue();
		assertThat(outbox.isRemoveOnCancelPolicy()).isTrue();
		assertThat(deadline).isNotSameAs(cleanup).isNotSameAs(outbox);
		assertThat(cleanup).isNotSameAs(outbox);
	}

	@Test
	void scheduledWorkloadsSelectTheirDedicatedSchedulers() throws NoSuchMethodException {
		assertThat(scheduled(TableGroupGameDeadlineScheduler.class, "advanceDueGames").scheduler())
				.isEqualTo(TableGroupSchedulingConfiguration.DEADLINE_SCHEDULER);
		assertThat(scheduled(TableGroupCleanupScheduler.class, "cleanupExpiredTableGroups").scheduler())
				.isEqualTo(TableGroupSchedulingConfiguration.CLEANUP_SCHEDULER);
		assertThat(scheduled(TableGroupNotificationOutboxScheduler.class, "dispatchDue").scheduler())
				.isEqualTo(TableGroupSchedulingConfiguration.OUTBOX_SCHEDULER);
		assertThat(scheduled(TableGroupNotificationOutboxScheduler.class, "cleanupPublished").scheduler())
				.isEqualTo(TableGroupSchedulingConfiguration.OUTBOX_SCHEDULER);
	}

	private Scheduled scheduled(Class<?> type, String methodName) throws NoSuchMethodException {
		return type.getDeclaredMethod(methodName).getAnnotation(Scheduled.class);
	}
}
