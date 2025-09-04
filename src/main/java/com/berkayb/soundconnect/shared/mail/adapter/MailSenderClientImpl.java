package com.berkayb.soundconnect.shared.mail.adapter;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.MailSenderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mailer send tabanli e-posta gonderim adapter'i
 * - MailSenderClient port'unu uygular
 * - Plain text ve/veya HTML govde tdestekler
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class MailSenderClientImpl implements MailSenderClient {
	
	private final RestTemplate restTemplate;
	
	@Value("${mailersend.api-key}")
	private String apiKey;
	
	@Value("${mailersend.from-email}")
	private String fromEmail;
	
	@Value("${mailersend.from-name}")
	private String fromName;
	
	private static final String MAILERSEND_URL = "https://api.mailersend.com/v1/email";
	
	@Override
	public void send(String to, String subject, String textBody, String htmlBody) {
		// Basit validasyon
		if (to == null || to.isBlank()) {
			throw new IllegalArgumentException("mail 'to' is required");
		}
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("mail 'subject' is required");
		}
		if ((textBody == null || textBody.isBlank()) && (htmlBody == null || htmlBody.isBlank())) {
			throw new IllegalArgumentException("either 'textBody' or 'htmlBody' must be provided");
		}
		
		// MailerSend body
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("from", Map.of("email", fromEmail, "name", fromName));
		body.put("to", List.of(Map.of("email", to)));
		body.put("subject", subject);
		if (textBody != null && !textBody.isBlank()) body.put("text", textBody);
		if (htmlBody != null && !htmlBody.isBlank()) body.put("html", htmlBody);
		
		// Header
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(apiKey);
		
		HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
		
		// Gönder
		try {
			ResponseEntity<String> response = restTemplate.exchange(MAILERSEND_URL, HttpMethod.POST, entity, String.class);
			if (response.getStatusCode().is2xxSuccessful()) {
				log.debug("Mail sent via MailerSend to={}, subject={}", to, subject);
			} else {
				log.error("MailerSend non-2xx response: status={}, body={}", response.getStatusCode(), response.getBody());
				throw new SoundConnectException(
						ErrorType.MAIL_QUEUE_ERROR,
						List.of("MailerSend status: " + response.getStatusCode(),
						        "Body: " + response.getBody(),
						        "To: " + to,
						        "Subject: " + subject)
				);
			}
		} catch (Exception e) {
			log.error("MailerSend call failed: to={}, subject={}, err={}", to, subject, e.toString());
			throw new SoundConnectException(ErrorType.MAIL_QUEUE_ERROR, List.of("To: " + to, "Subject: " + subject, "Error: " + e.getMessage()));
		}
		
	}
	
}