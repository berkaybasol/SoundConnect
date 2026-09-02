package com.berkayb.soundconnect.modules.tablegroup.game.scheduler;

import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameServiceImpl;
import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.mockito.Mockito.*;

class TableGroupGameDeadlineSchedulerTest {
	@Test
	void advancesEveryDueGameAndDoesNotStarveBatchAfterOneFailure() {
		TableGroupGameServiceImpl service = mock(TableGroupGameServiceImpl.class);
		TableGroupMetrics metrics = mock(TableGroupMetrics.class);
		TableGroupGameDeadlineScheduler scheduler = new TableGroupGameDeadlineScheduler(service, metrics);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		when(service.findDueIds(0)).thenReturn(List.of(first, second));
		doThrow(new IllegalStateException("race")).when(service).advanceDeadline(first);

		scheduler.advanceDueGames();

		verify(service).advanceDeadline(first);
		verify(service).advanceDeadline(second);
		verify(metrics).gameDeadlineTransitionFailed();
	}

	@Test
	void rotatesPastOneHundredPersistentFailuresToReachLaterDueGames() {
		TableGroupGameServiceImpl service = mock(TableGroupGameServiceImpl.class);
		TableGroupMetrics metrics = mock(TableGroupMetrics.class);
		TableGroupGameDeadlineScheduler scheduler = new TableGroupGameDeadlineScheduler(service, metrics);
		List<UUID> poison = java.util.stream.IntStream.range(0, 100)
				.mapToObj(ignored -> UUID.randomUUID())
				.toList();
		UUID valid = UUID.randomUUID();
		when(service.findDueIds(0)).thenReturn(poison);
		when(service.findDueIds(1)).thenReturn(List.of(valid));
		for (UUID gameId : poison) {
			doThrow(new IllegalStateException("poison aggregate"))
					.when(service).advanceDeadline(gameId);
		}

		scheduler.advanceDueGames();
		scheduler.advanceDueGames();

		verify(service).advanceDeadline(valid);
		verify(metrics, times(100)).gameDeadlineTransitionFailed();
	}

	@Test
	void metricFailureCannotStopRemainingDeadlineTransitions() {
		TableGroupGameServiceImpl service = mock(TableGroupGameServiceImpl.class);
		TableGroupMetrics metrics = mock(TableGroupMetrics.class);
		TableGroupGameDeadlineScheduler scheduler = new TableGroupGameDeadlineScheduler(service, metrics);
		UUID failed = UUID.randomUUID();
		UUID valid = UUID.randomUUID();
		when(service.findDueIds(0)).thenReturn(List.of(failed, valid));
		doThrow(new IllegalStateException("transition failure"))
				.when(service).advanceDeadline(failed);
		doThrow(new IllegalStateException("metrics unavailable"))
				.when(metrics).gameDeadlineTransitionFailed();

		scheduler.advanceDueGames();

		verify(service).advanceDeadline(valid);
	}
}
