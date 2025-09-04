package com.berkayb.soundconnect.modules.notification.mail;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.shared.mail.MailSenderClient; // -> eklendi
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailNotificationServiceImpl implements MailNotificationService {
	
	private final MailSenderClient mailSenderClient; // -> eklendi
	
	@Override
	public void maybeSendNotificationEmail(Notification notification, @Nullable Boolean emailForce) {
		Objects.requireNonNull(notification, "notification");
		
		// 1) Gönderim kararı
		if (!decide(emailForce, notification)) {
			log.debug("Mail skipped (decision=false): notifId={}, type={}, force={}",
			          notification.getId(), notification.getType(), emailForce);
			return;
		}
		
		// 2) Alıcı e-posta çöz (payload: recipientEmail/email)
		String to = resolveRecipientEmail(notification.getPayload());
		if (to == null || to.isBlank()) {
			log.warn("Mail skipped (no recipient email in payload): notifId={}, payloadKeys={}",
			         notification.getId(), notification.getPayload() != null ? notification.getPayload().keySet() : null);
			return;
		}
		
		// 3) Konu & içerik hazırla
		String subject = buildSubject(notification);
		String text = buildTextBody(notification);
		String html = null; // ileride HTML şablona geçebiliriz
		
		// 4) Gönder
		try {
			mailSenderClient.send(to, subject, text, html);
			log.debug("Email sent via MailSenderClient. to={}, subject={}, notifId={}", to, subject, notification.getId());
		} catch (Exception e) {
			log.error("Email send FAILED. to={}, subject={}, notifId={}, err={}", to, subject, notification.getId(), e.toString());
		}
	}
	
	@Override
	public void sendEmailForce(Notification notification) {
		maybeSendNotificationEmail(notification, true);
	}
	
	// --- Private helpers ---
	
	private boolean decide(@Nullable Boolean emailForce, Notification n) {
		if (emailForce != null) return emailForce; // TRUE => gönder, FALSE => asla
		return n.getType().isEmailRecommended();
	}
	
	@SuppressWarnings("unchecked")
	private String resolveRecipientEmail(Object payloadObj) {
		if (!(payloadObj instanceof Map<?, ?> map)) return null;
		Object email = map.get("recipientEmail");
		if (email == null) email = map.get("email");
		return email != null ? String.valueOf(email) : null;
	}
	
	private String buildSubject(Notification n) {
		String title = (n.getTitle() == null || n.getTitle().isBlank())
				? n.getType().getDefaultTitle()
				: n.getTitle();
		return "[SoundConnect] " + title;
	}
	
	private String buildTextBody(Notification n) {
		StringBuilder sb = new StringBuilder();
		String title = (n.getTitle() == null || n.getTitle().isBlank())
				? n.getType().getDefaultTitle()
				: n.getTitle();
		sb.append(title).append("\n\n");
		if (n.getMessage() != null && !n.getMessage().isBlank()) {
			sb.append(n.getMessage()).append("\n\n");
		}
		sb.append("Bu e-posta SoundConnect tarafından otomatik gönderildi.");
		return sb.toString();
	}
}