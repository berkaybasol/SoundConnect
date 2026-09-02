package com.berkayb.soundconnect.modules.tablegroup.scheduler;

import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Masa TTL cleanup scheduler.
 * Suresi dolan mesajlari ACTIVE'den INACTIVE ceker.
 * boylece listActiveTableGroups sonuclarinda gorunmicekler
 * chat gonderimi zaten bloklamistk ama bununla statusu de guncellemis olduk
 * ileride unread cache vs temizlemek icin merkezi nokta olcak
 */

@Component
@RequiredArgsConstructor
@Slf4j
public class TableGroupCleanupScheduler {
	
	private final TableGroupServiceImpl tableGroupService;
	private final TableGroupCleanupLock cleanupLock;
	
	// periyodik temizlik. fixedDelay = 60000 -> 1 dakikada bir tetiklenir
	@Scheduled(
			fixedDelay = 60_000L,
			initialDelay = 60_000L,
			scheduler = TableGroupSchedulingConfiguration.CLEANUP_SCHEDULER
	)
	public void cleanupExpiredTableGroups() {
		Optional<TableGroupCleanupLock.Lease> lease = cleanupLock.tryAcquire();
		if (lease.isEmpty()) {
			return;
		}
		try {
			runPhase("expire", tableGroupService::expireExpiredTableGroups);
			runPhase("chat-retention", tableGroupService::purgeRetainedChatMessages);
			runPhase("participant-retention", tableGroupService::purgeRetainedParticipantHistory);
		} finally {
			cleanupLock.release(lease.get());
		}
	}

	private void runPhase(String phase, Runnable work) {
		try {
			work.run();
		} catch (RuntimeException exception) {
			log.error("Table-group cleanup phase failed. phase={}", phase, exception);
		}
	}
}
