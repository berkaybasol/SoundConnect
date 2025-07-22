package com.berkayb.soundconnect.shared.mail.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {
	private final RestTemplate restTemplate = new RestTemplate();
	
	@Value("${mailersend.api-key}")
	private String apiKey;
	
	@Value("${mailersend.from-email}")
	private String fromEmail;
	
	@Value("${mailersend.from-name}")
	private String fromName;
	
	@Value("${mailersend.frontend-verify-url}")
	private String frontendVerifyUrl;
	
	@Override
	public void sendVerificationMail(String to, String verificationToken) {
		// Doğrulama linki
		String verifyLink = frontendVerifyUrl + "?token=" + verificationToken;
		
		// Mail içeriği
		String subject = "SoundConnect E-posta Doğrulama";
		String html = "<p>Merhaba,</p>" +
				"<p>SoundConnect hesabını tamamlamak için aşağıdaki bağlantıya tıkla:</p>" +
				"<a href='" + verifyLink + "'>E-postanı Doğrula</a>";
		
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
			restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
		} catch (Exception e) {
			// Geliştirirken log.info/log.error kullanabilirsin
			System.err.println("E-posta gönderilemedi: " + e.getMessage());
		}
	}
}