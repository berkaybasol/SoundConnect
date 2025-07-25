package com.berkayb.soundconnect.shared.mail.service;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Mailsend ile email dogrulama mesaji gonderen servis.
 * tum configler application.yml/.env'den alinir.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailServiceImpl implements MailService {
	/**
	 * RestTemplate dis servislere (MailerSend gibi) HTTP istegi atmak icin kullanilir.
	 * burada constructor ile almak icin Bean olarak config klasorunde @Configuration ile tanitim.
	 */
	private final RestTemplate restTemplate;
	
	@Value("${mailersend.api-key}")
	private String apiKey;
	
	@Value("${mailersend.from-email}")
	private String fromEmail;
	
	@Value("${mailersend.from-name}")
	private String fromName;
	
	@Value("${mailersend.frontend-verify-url}")
	private String frontendVerifyUrl;
	
	// kullaniciya dogrulama maili gonderen method.
	@Override
	public void sendVerificationMail(String to, String verificationToken) {
		// dogrulama linki
		String verifyLink = frontendVerifyUrl + "?token=" + verificationToken;
		
		// Mail içeriği
		String subject = "E-posta Doğrulama Gerekiyor";
		
		String html = "<p>Merhaba,</p>" +
				"<p>Hesabınızı etkinleştirmek için lütfen aşağıdaki bağlantıya tıklayın:</p>" +
				"<p><a href='" + verifyLink + "'>E-postanızı Doğrulayın</a></p>" +
				"<p>Bu bağlantı yalnızca kısa bir süre geçerlidir.</p>" +
				"<p>Teşekkürler,<br/>SoundConnect Ekibi &#10084;&#65039;</p>";
		
		// MailerSend API için JSON body
		Map<String, Object> body = Map.of(
				"from", Map.of(
						"email", fromEmail,
						"name", fromName
				),
				"to", List.of(Map.of(
						"email", to
				)),
				"subject", subject,
				"html", html
		);
		
		// HTTP header’lar
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(apiKey);
		
		HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
		
		// MailerSend endpoint’i
		String url = "https://api.mailersend.com/v1/email";
		
		
		// API’ye gönder (try-catch ile hatayı logla)
		try {
			ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
			if (response.getStatusCode().is2xxSuccessful()) {
				log.info("Verification mail sent via MailerSend to email={}", to);
			} else {
				log.error("MailerSend API returned error for email={}: status={}, body={}",
				          to, response.getStatusCode(), response.getBody());
				throw new SoundConnectException(
						ErrorType.MAIL_QUEUE_ERROR,
						List.of("MailerSend API status: " + response.getStatusCode(),
						        "Body: " + response.getBody(),
						        "Mail adresi: " + to)
				);
			}
		}  catch (Exception e) {
			log.error("Failed to send verification mail via MailerSend to email={}", to, e);
			throw new SoundConnectException(
					ErrorType.MAIL_QUEUE_ERROR,
					List.of("Mail adresi: " + to, "Hata: " + e.getMessage())
			);
		}
	}
}