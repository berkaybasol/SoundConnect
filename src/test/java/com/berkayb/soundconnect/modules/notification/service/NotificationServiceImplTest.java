package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.*;

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
	
	private NotificationServiceImpl service;
	
	private UUID userId;
	
	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		service = new NotificationServiceImpl(notificationRepository, notificationMapper, badgeCacheHelper);
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
		when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
				.thenReturn(page);
		
		NotificationResponseDto dto = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.MEDIA_UPLOAD_RECEVIED,
				"t", "m", false, null, Map.of()
		);
		when(notificationMapper.toDto(n)).thenReturn(dto);
		
		Page<NotificationResponseDto> result = service.getUserNotifications(userId, Pageable.unpaged());
		
		assertThat(result.getContent()).containsExactly(dto);
		verify(notificationRepository).findByRecipientIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class));
		verify(notificationMapper).toDto(n);
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
		
		when(notificationRepository.findByRecipientIdAndTypeInOrderByCreatedAtDesc(eq(userId), anyCollection(), any(Pageable.class)))
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
		
		Page<NotificationResponseDto> result = service.getUserNotificationsByTypes(userId, types, Pageable.ofSize(10));
		
		assertThat(result.getContent()).containsExactly(d1, d2);
		verify(notificationRepository).findByRecipientIdAndTypeInOrderByCreatedAtDesc(eq(userId), eq(types), any(Pageable.class));
		verify(notificationMapper, times(2)).toDto(any(Notification.class));
	}
	
	// ---------- getRecentNotifications ----------
	@Test
	@DisplayName("getRecentNotifications: repo top10 + mapper.toDtoList")
	void getRecentNotifications_ok() {
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.MEDIA_TRANSCODE_FAILED).read(false).build();
		when(notificationRepository.findTop10ByRecipientIdOrderByCreatedAtDesc(userId))
				.thenReturn(List.of(n));
		
		NotificationResponseDto dto = new NotificationResponseDto(UUID.randomUUID(), userId, n.getType(), "t","m", false, null, null);
		when(notificationMapper.toDtoList(List.of(n))).thenReturn(List.of(dto));
		
		List<NotificationResponseDto> list = service.getRecentNotifications(userId);
		
		assertThat(list).containsExactly(dto);
		verify(notificationRepository).findTop10ByRecipientIdOrderByCreatedAtDesc(userId);
		verify(notificationMapper).toDtoList(List.of(n));
	}
	
	// ---------- getUnreadCount ----------
	@Test
	@DisplayName("getUnreadCount: cache hit ise DB'ye inilmez")
	void getUnreadCount_cacheHit() {
		when(badgeCacheHelper.getCacheUnread(userId)).thenReturn(5L);
		
		long c = service.getUnreadCount(userId);
		
		assertThat(c).isEqualTo(5L);
		verify(notificationRepository, never()).countByRecipientIdAndReadIsFalse(any());
		verify(badgeCacheHelper, never()).setUnreadWithTtl(any(), anyLong());
	}
	
	@Test
	@DisplayName("getUnreadCount: cache miss ise DB sayılır ve cache set edilir")
	void getUnreadCount_cacheMiss() {
		when(badgeCacheHelper.getCacheUnread(userId)).thenReturn(null);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(7L);
		
		long c = service.getUnreadCount(userId);
		
		assertThat(c).isEqualTo(7L);
		InOrder in = inOrder(badgeCacheHelper, notificationRepository, badgeCacheHelper);
		in.verify(badgeCacheHelper).getCacheUnread(userId);
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
	@DisplayName("markAsRead: zaten read ise ALREADY_READ hatası fırlatır")
	void markAsRead_alreadyRead() {
		UUID notifId = UUID.randomUUID();
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.SOCIAL_NEW_FOLLOWER).read(true).build();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.of(n));
		
		assertThatThrownBy(() -> service.markAsRead(userId, notifId))
				.isInstanceOf(SoundConnectException.class);
		
		verify(notificationRepository, never()).markAsRead(any(), any());
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
		verify(badgeCacheHelper).decrementUnreadSafely(userId, 1, 3L);
	}
	
	// ---------- markAllAsRead ----------
	@Test
	@DisplayName("markAllAsRead: update=0 ise cache'e dokunmaz")
	void markAllAsRead_zero() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(0);
		
		int updated = service.markAllAsRead(userId);
		
		assertThat(updated).isEqualTo(0);
		verify(badgeCacheHelper, never()).setUnread(any(), anyLong());
	}
	
	@Test
	@DisplayName("markAllAsRead: update>0 ise cache unread=0 yapılır")
	void markAllAsRead_some() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(5);
		
		int updated = service.markAllAsRead(userId);
		
		assertThat(updated).isEqualTo(5);
		verify(badgeCacheHelper).setUnread(userId, 0);
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
		verify(badgeCacheHelper).decrementUnreadSafely(userId, 1, 4L);
	}
	
	@Test
	@DisplayName("deleteById: zaten read ise cache decrement çağrılmaz")
	void deleteById_alreadyRead() {
		UUID notifId = UUID.randomUUID();
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.MEDIA_TRANSCODE_READY).read(true).build();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.of(n));
		
		boolean ok = service.deleteById(userId, notifId);
		
		assertThat(ok).isTrue();
		verify(notificationRepository).delete(n);
		verify(badgeCacheHelper, never()).decrementUnreadSafely(any(), anyInt(), anyLong());
	}
}