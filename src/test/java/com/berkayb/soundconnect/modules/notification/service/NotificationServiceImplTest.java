package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.*;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceImplTest {
	
	@Mock
	private NotificationRepository notificationRepository;
	
	@Mock
	private NotificationMapper notificationMapper;
	
	@Mock
	private NotificationBadgeCacheHelper badgeCacheHelper;

	@Mock
	private NotificationWebSocketService notificationWebSocketService;
	
	private NotificationServiceImpl service;
	
	private UUID userId;
	
	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		service = new NotificationServiceImpl(
				notificationRepository,
				notificationMapper,
				badgeCacheHelper,
				notificationWebSocketService);
		userId = UUID.randomUUID();
	}
	
	// ---------- getUserNotifications ----------
	@Test
	@DisplayName("getUserNotifications: repository page -> mapper.toDto ile dönmeli")
	void getUserNotifications_ok() {
		Notification n = Notification.builder()
		                             .recipientId(userId)
		                             .type(NotificationType.MEDIA_UPLOAD_RECEVIED)
		                             .read(false)
		                             .build();
		Page<Notification> page = new PageImpl<>(List.of(n));
		when(notificationRepository.findByRecipientId(eq(userId), any(Pageable.class)))
				.thenReturn(page);
		
		NotificationResponseDto dto = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.MEDIA_UPLOAD_RECEVIED,
				"t", "m", false, null, Map.of()
		);
		when(notificationMapper.toDto(n)).thenReturn(dto);
		
		Page<NotificationResponseDto> result = service.getUserNotifications(userId, 0, 20);
		
		assertThat(result.getContent()).containsExactly(dto);
		ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
		verify(notificationRepository).findByRecipientId(
				eq(userId), pageableCaptor.capture());
		assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
		assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
		assertThat(pageableCaptor.getValue().getSort().toList())
				.extracting(Sort.Order::getProperty, Sort.Order::getDirection)
				.containsExactly(
						tuple("occurredAt", Sort.Direction.DESC),
						tuple("id", Sort.Direction.DESC)
				);
		verify(notificationMapper).toDto(n);
	}

	@Test
	@DisplayName("getUserNotifications: page ve size sinirlarini repository oncesi uygular")
	void getUserNotifications_rejectsInvalidPaginationBeforeRepositoryAccess() {
		assertThatThrownBy(() -> service.getUserNotifications(userId, 1001, 20))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
		assertThatThrownBy(() -> service.getUserNotifications(userId, 0, 101))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));

		verifyNoInteractions(notificationRepository, notificationMapper);
	}
	
	// ---------- getUserNotificationsByTypes ----------
	@Test
	@DisplayName("getUserNotificationsByTypes: filtreli çağrı ve mapping (Answer ile argümana göre DTO seç)")
	void getUserNotificationsByTypes_ok() {
		Set<NotificationType> types = EnumSet.of(NotificationType.MEDIA_TRANSCODE_READY, NotificationType.SOCIAL_NEW_FOLLOWER);
		
		Notification n1 = Notification.builder()
		                              .recipientId(userId)
		                              .type(NotificationType.MEDIA_TRANSCODE_READY)
		                              .read(false)
		                              .build();
		
		Notification n2 = Notification.builder()
		                              .recipientId(userId)
		                              .type(NotificationType.SOCIAL_NEW_FOLLOWER)
		                              .read(true)
		                              .build();
		
		Page<Notification> page = new PageImpl<>(List.of(n1, n2));
		
		when(notificationRepository.findByRecipientIdAndTypeIn(eq(userId), anyCollection(), any(Pageable.class)))
				.thenReturn(page);
		
		NotificationResponseDto d1 = new NotificationResponseDto(
				UUID.randomUUID(), userId, n1.getType(), "t1","m1", false, null, null);
		NotificationResponseDto d2 = new NotificationResponseDto(
				UUID.randomUUID(), userId, n2.getType(), "t2","m2", true, null, null);
		
		// Kritik kısım: hangi entity geldiyse ona uygun DTO’yu döndür.
		when(notificationMapper.toDto(any(Notification.class))).thenAnswer(inv -> {
			Notification arg = inv.getArgument(0, Notification.class);
			return arg.getType() == NotificationType.MEDIA_TRANSCODE_READY ? d1 : d2;
		});
		
		Page<NotificationResponseDto> result = service.getUserNotificationsByTypes(
				userId, types, 0, 10);
		
		assertThat(result.getContent()).containsExactly(d1, d2);
		verify(notificationRepository).findByRecipientIdAndTypeIn(eq(userId), eq(types), any(Pageable.class));
		verify(notificationMapper, times(2)).toDto(any(Notification.class));
	}
	
	// ---------- getRecentNotifications ----------
	@Test
	@DisplayName("getRecentNotifications: repo top10 + mapper.toDtoList")
	void getRecentNotifications_ok() {
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.MEDIA_TRANSCODE_FAILED).read(false).build();
		when(notificationRepository.findTop10ByRecipientIdOrderByOccurredAtDescIdDesc(userId))
				.thenReturn(List.of(n));
		
		NotificationResponseDto dto = new NotificationResponseDto(UUID.randomUUID(), userId, n.getType(), "t","m", false, null, null);
		when(notificationMapper.toDtoList(List.of(n))).thenReturn(List.of(dto));
		
		List<NotificationResponseDto> list = service.getRecentNotifications(userId);
		
		assertThat(list).containsExactly(dto);
		verify(notificationRepository).findTop10ByRecipientIdOrderByOccurredAtDescIdDesc(userId);
		verify(notificationMapper).toDtoList(List.of(n));
	}
	
	// ---------- getUnreadCount ----------
	@Test
	@DisplayName("getUnreadCount: stale Redis projection yerine DB source of truth kullanılır")
	void getUnreadCount_staleCacheCannotOverrideDatabase() {
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(5L);
		
		long c = service.getUnreadCount(userId);
		
		assertThat(c).isEqualTo(5L);
		verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper).setUnreadWithTtl(userId, 5L);
		verify(badgeCacheHelper, never()).getCacheUnread(any());
	}
	
	@Test
	@DisplayName("getUnreadCount: DB sayımı best-effort Redis projection'ı tazeler")
	void getUnreadCount_refreshesProjectionFromDatabase() {
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(7L);
		
		long c = service.getUnreadCount(userId);
		
		assertThat(c).isEqualTo(7L);
		InOrder in = inOrder(notificationRepository, badgeCacheHelper);
		in.verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		in.verify(badgeCacheHelper).setUnreadWithTtl(userId, 7L);
	}
	
	// ---------- markAsRead ----------
	@Test
	@DisplayName("markAsRead: bildirim bulunamazsa NOT_FOUND hatası fırlatır")
	void markAsRead_notFound() {
		UUID notifId = UUID.randomUUID();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.markAsRead(userId, notifId))
				.isInstanceOf(SoundConnectException.class);
		
		verify(notificationRepository, never()).markAsRead(any(), any());
	}
	
	@Test
	@DisplayName("markAsRead: zaten read ise idempotent success ve fresh badge projeksiyonu")
	void markAsRead_alreadyReadIsIdempotent() {
		UUID notifId = UUID.randomUUID();
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.SOCIAL_NEW_FOLLOWER).read(true).build();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.of(n));
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(6L);
		
		assertThatCode(() -> service.markAsRead(userId, notifId)).doesNotThrowAnyException();
		
		verify(notificationRepository, never()).markAsRead(any(), any());
		verify(badgeCacheHelper).setUnread(userId, 6L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 6L);
	}
	
	@Test
	@DisplayName("markAsRead: başarılı update sonrası fresh unread sayılır ve cache güvenli azaltılır")
	void markAsRead_success() {
		UUID notifId = UUID.randomUUID();
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.AUTH_EMAIL_VERIFIED).read(false).build();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.of(n));
		when(notificationRepository.markAsRead(notifId, userId)).thenReturn(1);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(3L);
		
		service.markAsRead(userId, notifId);
		
		verify(notificationRepository).markAsRead(notifId, userId);
		verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper).setUnread(userId, 3L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 3L);
	}
	
	// ---------- markAllAsRead ----------
	@Test
	@DisplayName("markAllAsRead: update=0 olsa da cihazlari fresh badge ile uzlastirir")
	void markAllAsRead_zero() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(0);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(0L);
		
		int updated = service.markAllAsRead(userId);
		
		assertThat(updated).isEqualTo(0);
		verify(badgeCacheHelper).setUnread(userId, 0L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 0L);
	}
	
	@Test
	@DisplayName("markAllAsRead: update>0 ise cache unread=0 yapılır")
	void markAllAsRead_some() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(5);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(0L);
		
		int updated = service.markAllAsRead(userId);
		
		assertThat(updated).isEqualTo(5);
		verify(badgeCacheHelper).setUnread(userId, 0L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 0L);
	}

	@Test
	@DisplayName("markDmConversationAsRead: cache ve WS badge projection committen sonra bir kez çalışır")
	void markDmConversationAsRead_projectsBadgeOnceAfterCommit() {
		UUID conversationId = UUID.randomUUID();
		when(notificationRepository.markUnreadDmNotificationsAsReadByConversation(
				userId, conversationId.toString())).thenReturn(2);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(4L);
		TransactionSynchronizationManager.initSynchronization();
		try {
			int updated = service.markDmConversationAsRead(userId, conversationId);

			assertThat(updated).isEqualTo(2);
			verify(notificationRepository, never()).countByRecipientIdAndReadIsFalse(any());
			verify(badgeCacheHelper, never()).setUnread(any(), anyLong());
			verifyNoInteractions(notificationWebSocketService);
			List<TransactionSynchronization> synchronizations =
					TransactionSynchronizationManager.getSynchronizations();
			assertThat(synchronizations).hasSize(1);
			synchronizations.getFirst().afterCommit();
			verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
			verify(badgeCacheHelper).setUnread(userId, 4L);
			verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 4L);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("notification mutation rollback olursa cache veya WS phantom badge üretmez")
	void mutationRollbackDoesNotProjectBadge() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(3);
		TransactionSynchronizationManager.initSynchronization();
		try {
			assertThat(service.markAllAsRead(userId)).isEqualTo(3);
			List<TransactionSynchronization> synchronizations =
					TransactionSynchronizationManager.getSynchronizations();
			assertThat(synchronizations).hasSize(1);
			synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

			verify(notificationRepository, never()).countByRecipientIdAndReadIsFalse(any());
			verifyNoInteractions(badgeCacheHelper, notificationWebSocketService);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("notification mutation: Redis projection failure does not suppress committed WS badge")
	void mutationCacheFailureStillProjectsDatabaseBadge() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(2);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(1L);
		doThrow(new IllegalStateException("redis down"))
				.when(badgeCacheHelper).setUnread(userId, 1L);

		assertThat(service.markAllAsRead(userId)).isEqualTo(2);

		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 1L);
	}

	@Test
	@DisplayName("notification mutation: committed DB recount failure does not publish a fabricated badge")
	void mutationRecountFailureDoesNotProjectBadge() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(2);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId))
				.thenThrow(new IllegalStateException("db unavailable"));

		assertThat(service.markAllAsRead(userId)).isEqualTo(2);

		verifyNoInteractions(badgeCacheHelper, notificationWebSocketService);
	}
	
	// ---------- deleteById ----------
	@Test
	@DisplayName("deleteById: bildirim yoksa NOT_FOUND hatası")
	void deleteById_notFound() {
		UUID notifId = UUID.randomUUID();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.deleteById(userId, notifId))
				.isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	@DisplayName("deleteById: unread bildirimi silerse fresh unread sayılır ve cache güvenli azaltılır")
	void deleteById_unread() {
		UUID notifId = UUID.randomUUID();
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.MEDIA_TRANSCODE_READY).read(false).build();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.of(n));
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(4L);
		
		boolean ok = service.deleteById(userId, notifId);
		
		assertThat(ok).isTrue();
		verify(notificationRepository).delete(n);
		verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper).setUnread(userId, 4L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 4L);
	}
	
	@Test
	@DisplayName("deleteById: read bildirim silinse de diğer cihazlara fresh badge yayınlanır")
	void deleteById_alreadyReadStillProjectsBadge() {
		UUID notifId = UUID.randomUUID();
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.MEDIA_TRANSCODE_READY).read(true).build();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.of(n));
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(2L);
		
		boolean ok = service.deleteById(userId, notifId);
		
		assertThat(ok).isTrue();
		verify(notificationRepository).delete(n);
		verify(badgeCacheHelper).setUnread(userId, 2L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 2L);
	}

	@Test
	@DisplayName("clearAll: committed fresh badge cache ve WS'e yansıtılır")
	void clearAll_projectsCommittedBadge() {
		when(notificationRepository.deleteByRecipientId(userId)).thenReturn(4);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(0L);

		assertThat(service.clearAll(userId)).isEqualTo(4);

		verify(badgeCacheHelper).setUnread(userId, 0L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 0L);
	}
}
