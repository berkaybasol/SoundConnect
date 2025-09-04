package com.berkayb.soundconnect.modules.notification.mail;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.listener.NotificationEventListener;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.mail.MailProducer;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Eski MailNotificationServiceImpl testinin yerini alır:
 * Artık e-posta kararı NotificationEventListener içinde veriliyor ve MailProducer'a kuyruklanıyor.
 * Bu test, emailForce ve type.emailRecommended mantığını doğrular.
 */
@ExtendWith(MockitoExtension.class)
class MailNotificationServiceImplTest {
	
	@Mock private NotificationRepository notificationRepository;
	@Mock private NotificationBadgeCacheHelper badgeCacheHelper;
	@Mock private NotificationMapper notificationMapper;
	@Mock private NotificationWebSocketService notificationWebSocketService;
	@Mock private MailProducer mailProducer;
	
	private NotificationEventListener listener;
	private UUID userId;
	
	@BeforeEach
	void setUp() {
		listener = new NotificationEventListener(
				notificationRepository,
				badgeCacheHelper,
				notificationMapper,
				notificationWebSocketService,
				mailProducer
		);
		userId = UUID.randomUUID();
		
		// Varsayılan ortak stub'lar (DB kaydı + badge hesapları akışı kırmasın)
		when(notificationRepository.countByRecipientIdAndReadIsFalse(any())).thenReturn(1L);
		when(badgeCacheHelper.getCacheUnread(any())).thenReturn(1L);
		when(notificationMapper.toDto(any())).thenAnswer(inv -> {
			Notification n = inv.getArgument(0);
			return new NotificationResponseDto(
					n.getId(), n.getRecipientId(), n.getType(),
					n.getTitle(), n.getMessage(), n.isRead(), null, n.getPayload()
			);
		});
		when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
			Notification n = inv.getArgument(0);
			// id set
			ReflectionTestUtils.setField(n, "id", UUID.randomUUID());
			return n;
		});
	}
	
	private NotificationInboundEvent evt(NotificationType t, String title, String msg, Map<String,Object> payload, Boolean emailForce) {
		return NotificationInboundEvent.builder()
		                               .recipientId(userId)
		                               .type(t)
		                               .title(title)
		                               .message(msg)
		                               .payload(payload)
		                               .emailForce(emailForce)
		                               .build();
	}
	
	@Test
	@DisplayName("emailForce=true → her durumda MailProducer.send çağrılır")
	void force_true_always_sends() {
		var event = evt(NotificationType.SOCIAL_NEW_FOLLOWER, "Yeni takipçi", "Selam",
		                Map.of("recipientEmail","u@e.com"), true);
		
		listener.handle(event);
		
		verify(mailProducer).send(any(MailSendRequest.class));
	}
	
	@Test
	@DisplayName("emailForce=false → hiçbir durumda mail gönderilmez")
	void force_false_never_sends() {
		var event = evt(NotificationType.AUTH_EMAIL_VERIFIED, "ok", "m",
		                Map.of("recipientEmail","u@e.com"), false);
		
		listener.handle(event);
		
		verifyNoInteractions(mailProducer);
	}
	
	@Test
	@DisplayName("emailForce=null → type.emailRecommended=true ise gönderir")
	void null_uses_recommendation_yes() {
		// MEDIA_TRANSCODE_FAILED → emailRecommended = true
		var event = evt(NotificationType.MEDIA_TRANSCODE_FAILED, null, "m",
		                Map.of("recipientEmail","u@e.com"), null);
		
		listener.handle(event);
		
		verify(mailProducer, times(1)).send(any(MailSendRequest.class));
	}
	
	@Test
	@DisplayName("emailForce=null → type.emailRecommended=false ise göndermez")
	void null_uses_recommendation_no() {
		// SOCIAL_NEW_FOLLOWER → emailRecommended = false
		var event = evt(NotificationType.SOCIAL_NEW_FOLLOWER, null, "m",
		                Map.of("recipientEmail","u@e.com"), null);
		
		listener.handle(event);
		
		verifyNoInteractions(mailProducer);
	}
	
	@Test
	@DisplayName("payload'da email yoksa mail gönderilmez (force=true olsa bile)")
	void no_email_skip() {
		var event = evt(NotificationType.MEDIA_TRANSCODE_FAILED, "x", "y", Map.of(), true);
		
		listener.handle(event);
		
		verifyNoInteractions(mailProducer);
	}
	
	@Test
	@DisplayName("MailProducer hata fırlatırsa swallow edilir (akış kırılmaz)")
	void producer_error_swallowed() {
		var event = evt(NotificationType.MEDIA_TRANSCODE_FAILED, "x", "y",
		                Map.of("recipientEmail","u@e.com"), true);
		
		doThrow(new RuntimeException("down")).when(mailProducer).send(any(MailSendRequest.class));
		
		assertThatNoException().isThrownBy(() -> listener.handle(event));
	}
}