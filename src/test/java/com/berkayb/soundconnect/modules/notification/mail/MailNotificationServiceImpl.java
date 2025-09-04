package com.berkayb.soundconnect.modules.notification.mail;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MailNotificationServiceImplTest {
	
	@Mock
	com.berkayb.soundconnect.shared.mail.MailSenderClient mailClient;
	
	private MailNotificationServiceImpl svc;
	
	@BeforeEach
	void setUp() { svc = new MailNotificationServiceImpl(mailClient); }
	
	private Notification n(NotificationType t, String title, String msg, Map<String,Object> payload) {
		return Notification.builder().recipientId(UUID.randomUUID()).type(t).title(title).message(msg).payload(payload).read(false).build();
	}
	
	@Test
	@DisplayName("emailForce=true → her durumda gönderir")
	void force_true_always_sends() {
		var notif = n(NotificationType.SOCIAL_NEW_FOLLOWER, "Yeni takipçi", "Selam", Map.of("recipientEmail","u@e.com"));
		svc.maybeSendNotificationEmail(notif, true);
		verify(mailClient).send(eq("u@e.com"), startsWith("[SoundConnect]"), contains("Selam"), isNull());
	}
	
	@Test @DisplayName("emailForce=false → asla göndermez")
	void force_false_never_sends() {
		var notif = n(NotificationType.AUTH_EMAIL_VERIFIED, "ok", "m", Map.of("recipientEmail","u@e.com"));
		svc.maybeSendNotificationEmail(notif, false);
		verifyNoInteractions(mailClient);
	}
	
	@Test @DisplayName("emailForce=null → type.emailRecommended'a göre karar")
	void null_uses_recommendation() {
		var yes = n(NotificationType.MEDIA_TRANSCODE_FAILED, null, "m", Map.of("recipientEmail","u@e.com")); // recommended=true
		var no  = n(NotificationType.SOCIAL_NEW_FOLLOWER, null, "m", Map.of("recipientEmail","u@e.com"));    // recommended=false
		
		svc.maybeSendNotificationEmail(yes, null);
		verify(mailClient, times(1)).send(anyString(), anyString(), anyString(), isNull());
		
		reset(mailClient);
		svc.maybeSendNotificationEmail(no, null);
		verifyNoInteractions(mailClient);
	}
	
	@Test @DisplayName("payload'da email yoksa göndermez")
	void no_email_skip() {
		var notif = n(NotificationType.MEDIA_TRANSCODE_FAILED, "x", "y", Map.of());
		svc.maybeSendNotificationEmail(notif, true);
		verifyNoInteractions(mailClient);
	}
	
	@Test @DisplayName("MailSenderClient hata fırlatırsa swallow edilir")
	void client_error_swallowed() {
		var notif = n(NotificationType.MEDIA_TRANSCODE_FAILED, "x", "y", Map.of("recipientEmail", "u@e.com"));
		doThrow(new RuntimeException("down")).when(mailClient).send(anyString(), anyString(), anyString(), any());
		// should not throw
		svc.maybeSendNotificationEmail(notif, true);
	}
}