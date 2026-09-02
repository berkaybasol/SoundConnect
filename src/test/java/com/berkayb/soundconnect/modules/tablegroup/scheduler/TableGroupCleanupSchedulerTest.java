package com.berkayb.soundconnect.modules.tablegroup.scheduler;

import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.*;

/**
 * Scheduler sadece service'i tetikleyip hatayi log'layip yutuyor mu kontrol ediyoruz.
 */
class TableGroupCleanupSchedulerTest {
	
	@Test
	void cleanupExpiredTableGroups_whenServiceSucceeds_shouldCallExpireExpiredTableGroups() {
		// given
		TableGroupServiceImpl service = mock(TableGroupServiceImpl.class);
		TableGroupCleanupLock lock = mock(TableGroupCleanupLock.class);
		TableGroupCleanupLock.Lease lease = new TableGroupCleanupLock.Lease("token");
		when(lock.tryAcquire()).thenReturn(Optional.of(lease));
		TableGroupCleanupScheduler scheduler = new TableGroupCleanupScheduler(service, lock);
		
		// when
		scheduler.cleanupExpiredTableGroups();
		
		// then
		verify(service, times(1)).expireExpiredTableGroups();
		verify(service, times(1)).purgeRetainedChatMessages();
		verify(service, times(1)).purgeRetainedParticipantHistory();
		verify(lock).release(lease);
	}
	
	@Test
	void cleanupExpiredTableGroups_whenExpiryFails_shouldContinueAllIndependentPhases() {
		// given
		TableGroupServiceImpl service = mock(TableGroupServiceImpl.class);
		doThrow(new RuntimeException("boom")).when(service).expireExpiredTableGroups();
		TableGroupCleanupLock lock = mock(TableGroupCleanupLock.class);
		TableGroupCleanupLock.Lease lease = new TableGroupCleanupLock.Lease("token");
		when(lock.tryAcquire()).thenReturn(Optional.of(lease));

		TableGroupCleanupScheduler scheduler = new TableGroupCleanupScheduler(service, lock);
		
		// when - exception fırlatmaması gerekir
		scheduler.cleanupExpiredTableGroups();
		
		// then
		verify(service, times(1)).expireExpiredTableGroups();
		verify(service, times(1)).purgeRetainedChatMessages();
		verify(service, times(1)).purgeRetainedParticipantHistory();
		verify(lock).release(lease);
	}

	@Test
	void cleanupExpiredTableGroups_whenChatRetentionFails_shouldStillRunParticipantRetention() {
		TableGroupServiceImpl service = mock(TableGroupServiceImpl.class);
		doThrow(new RuntimeException("boom")).when(service).purgeRetainedChatMessages();
		TableGroupCleanupLock lock = mock(TableGroupCleanupLock.class);
		TableGroupCleanupLock.Lease lease = new TableGroupCleanupLock.Lease("token");
		when(lock.tryAcquire()).thenReturn(Optional.of(lease));

		new TableGroupCleanupScheduler(service, lock).cleanupExpiredTableGroups();

		verify(service).expireExpiredTableGroups();
		verify(service).purgeRetainedChatMessages();
		verify(service).purgeRetainedParticipantHistory();
		verify(lock).release(lease);
	}

	@Test
	void cleanupExpiredTableGroups_whenAnotherNodeOwnsLease_shouldSkipWork() {
		TableGroupServiceImpl service = mock(TableGroupServiceImpl.class);
		TableGroupCleanupLock lock = mock(TableGroupCleanupLock.class);
		when(lock.tryAcquire()).thenReturn(Optional.empty());

		new TableGroupCleanupScheduler(service, lock).cleanupExpiredTableGroups();

		verifyNoInteractions(service);
		verify(lock, never()).release(any());
	}
}
