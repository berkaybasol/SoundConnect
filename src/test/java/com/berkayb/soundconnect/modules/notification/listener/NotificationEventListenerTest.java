package com.berkayb.soundconnect.modules.notification.listener;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.mail.MailProducer;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {
	
	@Mock private NotificationRepository notificationRepository;
	@Mock private NotificationBadgeCacheHelper badgeCacheHelper;
	@Mock private NotificationMapper notificationMapper;
	@Mock private NotificationWebSocketService notificationWebSocketService;
	@Mock private MailProducer mailProducer; // YENİ: MailProducer mock'u
	
	private NotificationEventListener listener;
	
	private UUID userId;
	
	@BeforeEach
	void setUp() {
		listener = new NotificationEventListener(
				notificationRepository,
				badgeCacheHelper,
				notificationMapper,
				notificationWebSocketService,
				mailProducer   // YENİ: mailProducer eklendi
		);
		userId = UUID.randomUUID();
	}
	
	@Test
	@DisplayName("Invalid event: recipientId veya type yoksa erken return; hiçbir yan etki yok")
	void handle_invalidEvent_skips() {
		// recipient yok
		NotificationInboundEvent e1 = NotificationInboundEvent.builder()
		                                                      .recipientId(null)
		                                                      .type(NotificationType.MEDIA_UPLOAD_RECEIVED)
		                                                      .title("x")
		                                                      .message("y")
		                                                      .build();
		
		// type yok
		NotificationInboundEvent e2 = NotificationInboundEvent.builder()
		                                                      .recipientId(userId)
		                                                      .type(null)
		                                                      .title("x")
		                                                      .message("y")
		                                                      .build();
		
		listener.handle(e1);
		listener.handle(e2);
		
		verifyNoInteractions(notificationRepository, badgeCacheHelper, notificationMapper,
		                     notificationWebSocketService, mailProducer); // YENİ: mailProducer eklendi
	}
	
	@Test
	@DisplayName("Happy path: save → unread count → cache set → WS notif+badge → mail pipeline'a gönderim")
	void handle_happyPath() {
		NotificationInboundEvent event = NotificationInboundEvent.builder()
		                                                         .recipientId(userId)
		                                                         .type(NotificationType.MEDIA_TRANSCODE_FAILED) // <-- emailRecommended=true
		                                                         .title("Medya işleme başarısız")
		                                                         .message("Parça işlenemedi")
		                                                         .payload(Map.of("recipientEmail", "user@example.com"))
		                                                         .emailForce(null) // type.emailRecommended() devrede
		                                                         .build();
		
		// repo.save(entity) → entity + id dönsün
		Notification saved = Notification.builder()
		                                 .recipientId(userId)
		                                 .type(NotificationType.MEDIA_TRANSCODE_FAILED)
		                                 .title("Medya işleme başarısız")
		                                 .message("Parça işlenemedi")
		                                 .payload(Map.of("recipientEmail", "user@example.com"))
		                                 .read(false)
		                                 .build();
		// id set edelim
		UUID notifId = UUID.randomUUID();
		org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", notifId);
		
		when(notificationRepository.save(any(Notification.class))).thenReturn(saved);
		
		// unread count → cache set
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(7L);
		
		// mapper
		NotificationResponseDto dto = new NotificationResponseDto(
				notifId, userId, NotificationType.MEDIA_TRANSCODE_FAILED,
				"Medya işleme başarısız", "Parça işlenemedi", false, null, Map.of("recipientEmail", "user@example.com")
		);
		when(notificationMapper.toDto(saved)).thenReturn(dto);
		
		// badge için cache okununca 7L gelsin
		when(badgeCacheHelper.getCacheUnread(userId)).thenReturn(7L);
		
		// act
		listener.handle(event);
		
		// assert: DB save doğru alanlarla çağrılmış mı (argument captor ile temel doğrulama)
		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository).save(captor.capture());
		Notification toSave = captor.getValue();
		assertThat(toSave.getRecipientId()).isEqualTo(userId);
		assertThat(toSave.getType()).isEqualTo(NotificationType.MEDIA_TRANSCODE_FAILED);
		assertThat(toSave.isRead()).isFalse();
		
		// unread count → cache set
		verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper).setUnreadWithTtl(userId, 7L);
		
		// WS push (notif + badge)
		verify(notificationMapper).toDto(saved);
		verify(notificationWebSocketService).sendNotificationToUser(userId, dto);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 7L);
		
		// mail kararı: **Artık mailProducer üzerinden**
		verify(mailProducer).send(any(MailSendRequest.class));
	}
	
	@Test
	@DisplayName("WS ve Mail hata atsa bile swallow edilir; notif WS fail olursa badge push yapılmaz (aynı try bloğu)")
	void handle_swallowWsAndMailErrors() {
		NotificationInboundEvent event = NotificationInboundEvent.builder()
		                                                         .recipientId(userId)
		                                                         .type(NotificationType.SOCIAL_NEW_FOLLOWER)
		                                                         .title("Yeni takipçi")
		                                                         .message("Selam!")
		                                                         .payload(Map.of("recipientEmail", "user@example.com"))
		                                                         .emailForce(true)
		                                                         .build();
		
		Notification saved = Notification.builder()
		                                 .recipientId(userId)
		                                 .type(NotificationType.SOCIAL_NEW_FOLLOWER)
		                                 .title("Yeni takipçi")
		                                 .message("Selam!")
		                                 .payload(Map.of("recipientEmail", "user@example.com"))
		                                 .read(false)
		                                 .build();
		UUID notifId = UUID.randomUUID();
		org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", notifId);
		
		when(notificationRepository.save(any(Notification.class))).thenReturn(saved);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(9L);
		
		NotificationResponseDto dto = new NotificationResponseDto(
				notifId, userId, NotificationType.SOCIAL_NEW_FOLLOWER,
				"Yeni takipçi", "Selam!", false, null, Map.of("recipientEmail", "user@example.com")
		);
		when(notificationMapper.toDto(saved)).thenReturn(dto);
		
		// WS notif fail → aynı try içinde olduğu için badge push çağrılmayacak
		doThrow(new RuntimeException("ws down"))
				.when(notificationWebSocketService).sendNotificationToUser(userId, dto);
		
		// Mail de fail etsin ama swallow edilsin (simule amaçlı)
		doThrow(new RuntimeException("mail down"))
				.when(mailProducer).send(any(MailSendRequest.class));
		
		// act
		listener.handle(event);
		
		// assert
		verify(notificationRepository).save(any(Notification.class));
		verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper).setUnreadWithTtl(userId, 9L);
		
		verify(notificationWebSocketService).sendNotificationToUser(userId, dto);
		verify(notificationWebSocketService, never()).sendUnreadBadgeToUser(any(), anyLong());
		
		// MailProducer çağrılır ama hata swallow edilir
		verify(mailProducer).send(any(MailSendRequest.class));
	}
	
}