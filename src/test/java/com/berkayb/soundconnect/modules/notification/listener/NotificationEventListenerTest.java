package com.berkayb.soundconnect.modules.notification.listener;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.junit.jupiter.api.extension.ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {
	
	@Mock private NotificationRepository notificationRepository;
	@Mock private NotificationBadgeCacheHelper badgeCacheHelper;
	@Mock private NotificationMapper notificationMapper;
	@Mock private NotificationWebSocketService notificationWebSocketService;
	@Mock private MailProducer mailProducer; // YENİ: MailProducer mock'u
	@Mock private NotificationService notificationService;
	@Mock private NotificationReceiptRepository receiptRepository;
	
	private NotificationEventListener listener;
	
	private UUID userId;

	@Test
	void erasedOrDeletedSourceRetainsReplayReceiptWithoutRecreatingInboxOrDeliveringSnapshots() {
		var policy = mock(com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy.class);
		var guarded = new NotificationEventListener(notificationRepository, badgeCacheHelper, notificationMapper,
				notificationWebSocketService, mailProducer, notificationService, receiptRepository, policy);
		var event = NotificationInboundEvent.builder().eventId(UUID.randomUUID()).recipientId(userId)
				.type(NotificationType.DM_NEW_MESSAGE).title("Old identity").message("Old text").build();
		when(policy.eligible(event)).thenReturn(false);
		guarded.handle(event);
		var order = inOrder(policy, receiptRepository);
		order.verify(policy).eligible(event);
		order.verify(receiptRepository).claim(event.eventId(), userId);
		verify(notificationRepository, never()).saveAndFlush(any());
		verifyNoInteractions(notificationWebSocketService, mailProducer, badgeCacheHelper);
	}
	
	@BeforeEach
	void setUp() {
		listener = new NotificationEventListener(
				notificationRepository,
				badgeCacheHelper,
				notificationMapper,
				notificationWebSocketService,
				mailProducer,
				notificationService,
				receiptRepository, com.berkayb.soundconnect.support.DeliveryPolicyTestSupport.immediateAllowedPolicy()
		);
		lenient().when(notificationService.refreshActorIdentityForDelivery(any()))
				.thenAnswer(call -> call.getArgument(0));
		userId = UUID.randomUUID();
		lenient().when(receiptRepository.claim(any(), any())).thenReturn(1);
	}
	
	@Test
	@DisplayName("Invalid event: recipientId veya type yoksa erken return; hiçbir yan etki yok")
	void handle_invalidEvent_skips() {
		// recipient yok
		NotificationInboundEvent e1 = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
		                                                      .recipientId(null)
		                                                      .type(NotificationType.MEDIA_UPLOAD_RECEVIED)
		                                                      .title("x")
		                                                      .message("y")
		                                                      .build();
		
		// type yok
		NotificationInboundEvent e2 = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
		                                                      .recipientId(userId)
		                                                      .type(null)
		                                                      .title("x")
		                                                      .message("y")
		                                                      .build();
		
		listener.handle(e1);
		listener.handle(e2);
		assertThatThrownBy(() -> listener.handle(new NotificationInboundEvent(null, userId, NotificationType.BAND_INVITE_RECEIVED,
				"Legacy event without replay identity", "message", Map.of(), false, Instant.now())))
				.isInstanceOf(org.springframework.amqp.AmqpRejectAndDontRequeueException.class);
		
		verifyNoInteractions(notificationRepository, badgeCacheHelper, notificationMapper,
		                     notificationWebSocketService, mailProducer, receiptRepository);
	}
	
	@Test
	@DisplayName("Happy path: save → unread count → cache set → WS notif+badge → mail pipeline'a gönderim")
	void handle_happyPath() {
		Instant occurredAt = Instant.parse("2026-08-11T09:15:00Z");
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
		                                                         .recipientId(userId)
		                                                         .type(NotificationType.MEDIA_TRANSCODE_FAILED) // <-- emailRecommended=true
		                                                         .title("Medya işleme başarısız")
		                                                         .message("Parça işlenemedi")
		                                                         .payload(Map.of("recipientEmail", "user@example.com"))
		                                                         .emailForce(null) // type.emailRecommended() devrede
		                                                         .occurredAt(occurredAt)
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
		
		when(notificationRepository.saveAndFlush(any(Notification.class))).thenReturn(saved);
		
		// unread count → cache set
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(7L);
		
		// mapper
		NotificationResponseDto dto = new NotificationResponseDto(
				notifId, userId, NotificationType.MEDIA_TRANSCODE_FAILED,
				"Medya işleme başarısız", "Parça işlenemedi", false, null, Map.of("recipientEmail", "user@example.com")
		);
		when(notificationMapper.toDto(saved)).thenReturn(dto);
		
		// act
		listener.handle(event);
		
		// assert: DB save doğru alanlarla çağrılmış mı (argument captor ile temel doğrulama)
		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository).saveAndFlush(captor.capture());
		Notification toSave = captor.getValue();
		assertThat(toSave.getRecipientId()).isEqualTo(userId);
		assertThat(toSave.getType()).isEqualTo(NotificationType.MEDIA_TRANSCODE_FAILED);
		assertThat(toSave.isRead()).isFalse();
		assertThat(toSave.getOccurredAt()).isEqualTo(occurredAt);
		
		// unread count → cache set
		verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper).setUnreadWithTtl(userId, 7L);
		
		// WS push (notif + badge)
		verify(notificationMapper).toDto(saved);
		verify(notificationWebSocketService).sendNotificationToUser(userId, dto);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 7L);
		verify(badgeCacheHelper, never()).getCacheUnread(any());
		
		// mail kararı: **Artık mailProducer üzerinden**
		verify(mailProducer).send(any(MailSendRequest.class));
	}
	
	@Test
	@DisplayName("Notification WS ve mail hata atsa da DB badge push bağımsız çalışır")
	void handle_swallowWsAndMailErrors() {
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
		                                                         .recipientId(userId)
		                                                         .type(NotificationType.SOCIAL_NEW_FOLLOWER)
		                                                         .title("Yeni takipçi")
		                                                         .message("Selam!")
		                                                         .payload(Map.of("recipientEmail", "user@example.com"))
		                                                         .emailForce(true)
		                                                         .occurredAt(Instant.parse("2026-08-11T09:16:00Z"))
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
		
		when(notificationRepository.saveAndFlush(any(Notification.class))).thenReturn(saved);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(9L);
		
		NotificationResponseDto dto = new NotificationResponseDto(
				notifId, userId, NotificationType.SOCIAL_NEW_FOLLOWER,
				"Yeni takipçi", "Selam!", false, null, Map.of("recipientEmail", "user@example.com")
		);
		when(notificationMapper.toDto(saved)).thenReturn(dto);
		
		// Notification frame fail etse de DB kaynaklı badge frame'i bağımsızdır.
		doThrow(new RuntimeException("ws down"))
				.when(notificationWebSocketService).sendNotificationToUser(userId, dto);
		
		// Mail de fail etsin ama swallow edilsin (simule amaçlı)
		doThrow(new RuntimeException("mail down"))
				.when(mailProducer).send(any(MailSendRequest.class));
		
		// act
		listener.handle(event);
		
		// assert
		verify(notificationRepository).saveAndFlush(any(Notification.class));
		verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper).setUnreadWithTtl(userId, 9L);
		
		verify(notificationWebSocketService).sendNotificationToUser(userId, dto);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 9L);
		
		// MailProducer çağrılır ama hata swallow edilir
		verify(mailProducer).send(any(MailSendRequest.class));
	}

	@Test
	@DisplayName("Gecikmiş takip bildirimi güncel güvenli kimlikle WebSocket üzerinden iletilir")
	void delayedActorIdentityIsRefreshedBeforeRealtimePush() {
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID()).recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_BAND_FOLLOWER).title("Old name").message("message")
				.emailForce(false).occurredAt(Instant.now()).build();
		Notification stored = Notification.builder().recipientId(userId).type(event.type())
				.title(event.title()).message(event.message()).occurredAt(event.occurredAt()).build();
		NotificationResponseDto stale = new NotificationResponseDto(UUID.randomUUID(), userId, event.type(),
				"Old name", "message", false, event.occurredAt(), Map.of("followerUsername", "Old name"));
		NotificationResponseDto safe = new NotificationResponseDto(stale.id(), userId, event.type(),
				"ghost_alias", "message", false, event.occurredAt(), Map.of("followerUsername", "ghost_alias"));
		when(notificationRepository.saveAndFlush(any())).thenReturn(stored);
		when(notificationMapper.toDto(stored)).thenReturn(stale);
		when(notificationService.refreshActorIdentityForDelivery(stale)).thenReturn(safe);

		listener.handle(event);

		verify(notificationWebSocketService).sendNotificationToUser(userId, safe);
		verify(notificationWebSocketService, never()).sendNotificationToUser(userId, stale);
	}

	@Test
	void bandNotificationDeliveryDoesNotOpenAnActorIdentityTransaction() {
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID()).recipientId(userId)
				.type(NotificationType.BAND_INVITE_RECEIVED).title("Grup daveti").message("message")
				.emailForce(false).occurredAt(Instant.now()).build();
		Notification stored = Notification.builder().recipientId(userId).type(event.type())
				.title(event.title()).message(event.message()).occurredAt(event.occurredAt()).build();
		NotificationResponseDto dto = new NotificationResponseDto(UUID.randomUUID(), userId, event.type(),
				event.title(), event.message(), false, event.occurredAt(), Map.of());
		when(notificationRepository.saveAndFlush(any())).thenReturn(stored);
		when(notificationMapper.toDto(stored)).thenReturn(dto);

		listener.handle(event);

		verify(notificationWebSocketService).sendNotificationToUser(userId, dto);
		verifyNoInteractions(notificationService);
	}

	@Test
	@DisplayName("Legacy event occurredAt taşımıyorsa tüketim zamanı audit fallback olarak kaydedilir")
	void handle_legacyEventFallsBackToConsumptionTime() {
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_FOLLOWER)
				.title("Yeni takipçi")
				.message("Selam")
				.emailForce(false)
				.build();
		when(notificationRepository.saveAndFlush(any(Notification.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(1L);
		Instant before = Instant.now();

		listener.handle(event);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getOccurredAt())
				.isBetween(before, Instant.now());
	}

	@Test
	@DisplayName("Optional title/message DB NOT NULL sözleşmesine güvenli varsayılanlarla yazılır")
	void handle_optionalTextUsesPersistenceSafeDefaults() {
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_FOLLOWER)
				.title("  ")
				.message(null)
				.emailForce(false)
				.occurredAt(Instant.parse("2026-08-11T09:16:30Z"))
				.build();
		when(notificationRepository.saveAndFlush(any(Notification.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(1L);

		listener.handle(event);

		ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
		verify(notificationRepository).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getTitle())
				.isEqualTo(NotificationType.SOCIAL_NEW_FOLLOWER.getDefaultTitle());
		assertThat(captor.getValue().getMessage()).isEmpty();
	}

	@Test
	@DisplayName("DB kolon sınırını aşan event poison retry yerine validation ile atlanır")
	void handle_oversizedTextSkipsBeforePersistence() {
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_FOLLOWER)
				.title("x".repeat(161))
				.message("message")
				.build();

		listener.handle(event);

		verifyNoInteractions(notificationRepository, badgeCacheHelper, notificationMapper,
				notificationWebSocketService, mailProducer);
	}

	@Test
	@DisplayName("Redis projection fail/null olsa da WS badge DB fresh unread değerini kullanır")
	void handle_cacheFailureStillBroadcastsDatabaseUnread() {
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_FOLLOWER)
				.title("Yeni takipçi")
				.message("Selam")
				.emailForce(false)
				.occurredAt(Instant.parse("2026-08-11T09:17:00Z"))
				.build();
		Notification saved = Notification.builder()
				.recipientId(userId)
				.type(event.type())
				.title(event.title())
				.message(event.message())
				.occurredAt(event.occurredAt())
				.read(false)
				.build();
		when(notificationRepository.saveAndFlush(any(Notification.class))).thenReturn(saved);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(12L);
		doThrow(new IllegalStateException("redis unavailable"))
				.when(badgeCacheHelper).setUnreadWithTtl(userId, 12L);

		listener.handle(event);

		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 12L);
		verify(badgeCacheHelper, never()).getCacheUnread(any());
	}

	@Test
	@DisplayName("DB transaction commit olmadan cache, WS veya mail yan etkisi oluşmaz")
	void handle_defersExternalSideEffectsUntilCommit() {
		Instant occurredAt = Instant.parse("2026-08-11T09:18:00Z");
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_FOLLOWER)
				.title("Yeni takipçi")
				.message("Selam")
				.emailForce(false)
				.occurredAt(occurredAt)
				.build();
		Notification saved = Notification.builder()
				.recipientId(userId).type(event.type()).title(event.title()).message(event.message())
				.occurredAt(occurredAt).read(false).build();
		when(notificationRepository.saveAndFlush(any(Notification.class))).thenReturn(saved);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(2L);
		TransactionSynchronizationManager.initSynchronization();
		try {
			listener.handle(event);

			verify(notificationRepository, never()).countByRecipientIdAndReadIsFalse(any());
			verifyNoInteractions(badgeCacheHelper, notificationMapper,
					notificationWebSocketService, mailProducer);
			List<TransactionSynchronization> synchronizations =
					TransactionSynchronizationManager.getSynchronizations();
			assertThat(synchronizations).hasSize(1);
			synchronizations.getFirst().afterCommit();

			verify(badgeCacheHelper).setUnreadWithTtl(userId, 2L);
			verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 2L);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("DB transaction rollback olursa cache, WS veya mail phantom side effect üretmez")
	void handle_rollbackDoesNotDispatchExternalSideEffects() {
		NotificationInboundEvent event = NotificationInboundEvent.builder().eventId(UUID.randomUUID())
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_FOLLOWER)
				.title("Yeni takipçi")
				.message("Selam")
				.emailForce(false)
				.occurredAt(Instant.parse("2026-08-11T09:19:00Z"))
				.build();
		when(notificationRepository.saveAndFlush(any(Notification.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		TransactionSynchronizationManager.initSynchronization();
		try {
			listener.handle(event);
			List<TransactionSynchronization> synchronizations =
					TransactionSynchronizationManager.getSynchronizations();
			assertThat(synchronizations).hasSize(1);
			synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

			verify(notificationRepository, never()).countByRecipientIdAndReadIsFalse(any());
			verifyNoInteractions(badgeCacheHelper, notificationMapper,
					notificationWebSocketService, mailProducer);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}
	
}
