package com.berkayb.soundconnect.modules.tablegroup.scheduler;

import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
	
	// periyodik temizlik. fixedDelay = 60000 -> 1 dakikada bir tetiklenir
	@Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
	public void cleanupExpiredTableGroups() {
		try {
			tableGroupService.expireExpiredTableGroups();
		} catch (Exception e) {
			log.error("Error while expiring table groups: {}", e.toString());
		}
	}
}