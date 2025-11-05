package com.berkayb.soundconnect.modules.tablegroup.scheduler;

import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.*;

/**
 * Scheduler sadece service'i tetikleyip hatayi log'layip yutuyor mu kontrol ediyoruz.
 */
class TableGroupCleanupSchedulerTest {
	
	@Test
	void cleanupExpiredTableGroups_whenServiceSucceeds_shouldCallExpireExpiredTableGroups() {
		// given
		TableGroupServiceImpl service = mock(TableGroupServiceImpl.class);
		TableGroupCleanupScheduler scheduler = new TableGroupCleanupScheduler(service);
		
		// when
		scheduler.cleanupExpiredTableGroups();
		
		// then
		verify(service, times(1)).expireExpiredTableGroups();
	}
	
	@Test
	void cleanupExpiredTableGroups_whenServiceThrows_shouldSwallowException() {
		// given
		TableGroupServiceImpl service = mock(TableGroupServiceImpl.class);
		doThrow(new RuntimeException("boom")).when(service).expireExpiredTableGroups();
		
		TableGroupCleanupScheduler scheduler = new TableGroupCleanupScheduler(service);
		
		// when - exception fırlatmaması gerekir
		scheduler.cleanupExpiredTableGroups();
		
		// then
		verify(service, times(1)).expireExpiredTableGroups();
		// Exception dışarı fırlamadığı için test patlamıyor -> davranış doğru
	}
}