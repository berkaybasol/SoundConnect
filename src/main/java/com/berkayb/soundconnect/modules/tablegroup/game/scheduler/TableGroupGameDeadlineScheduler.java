package com.berkayb.soundconnect.modules.tablegroup.game.scheduler;

import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameServiceImpl;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import com.berkayb.soundconnect.modules.tablegroup.scheduler.TableGroupSchedulingConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class TableGroupGameDeadlineScheduler {
	private final TableGroupGameServiceImpl gameService;
	private final TableGroupMetrics metrics;
	private int duePage;

	@Scheduled(
			fixedDelayString = "${app.table-group.game.deadline-tick-ms:1000}",
			initialDelayString = "${app.table-group.game.deadline-initial-delay-ms:1000}",
			scheduler = TableGroupSchedulingConfiguration.DEADLINE_SCHEDULER
	)
	public void advanceDueGames() {
		List<UUID> dueIds = gameService.findDueIds(duePage);
		if (dueIds.isEmpty()) {
			// Rotating bounded pages ensure a persistent poison first page cannot
			// starve later valid deadlines forever. Successful rows disappear, so
			// restart from the front after reaching the current end.
			duePage = 0;
			return;
		}
		duePage = duePage == Integer.MAX_VALUE ? 0 : duePage + 1;
		for (UUID gameId : dueIds) {
			try {
				gameService.advanceDeadline(gameId);
			} catch (RuntimeException exception) {
				// Each game owns its transaction. One corrupt/racing aggregate must
				// not starve the remaining bounded batch.
				log.warn("Table-group game deadline transition failed: gameId={}", gameId, exception);
				try {
					metrics.gameDeadlineTransitionFailed();
				} catch (RuntimeException metricFailure) {
					log.debug("Table-group game deadline failure metric failed", metricFailure);
				}
			}
		}
	}
}
