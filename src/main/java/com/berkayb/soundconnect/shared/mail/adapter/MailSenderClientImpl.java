package com.berkayb.soundconnect.shared.mail.adapter;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MailSenderClientImpl implements MailSenderClient {
	
	private final MailJobHelper helper;
	private final WebClient mailerSendWebClient; //
	
	@Value("${mailersend.from-email}")
	private String fromEmail;
	
	@Value("${mailersend.from-name:SoundConnect}")
	private String fromName;
	
	@Value("${mailersend.readTimeoutSec:8}")
	private int readTimeoutSec;
	
	@Override
	public void send(String to, String subject, String textBody, String htmlBody) {
		if (to == null || to.isBlank()) {
			throw new IllegalArgumentException("mail 'to' is required");
		}
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("mail 'subject' is required");
		}
		if ((textBody == null || textBody.isBlank()) && (htmlBody == null || htmlBody.isBlank())) {
			throw new IllegalArgumentException("either 'textBody' or 'htmlBody' must be provided");
		}
		
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("from", Map.of("email", fromEmail, "name", fromName));
		body.put("to", List.of(Map.of("email", to)));
		body.put("subject", subject);
		if (textBody != null && !textBody.isBlank()) body.put("text", textBody);
		if (htmlBody != null && !htmlBody.isBlank()) body.put("html", htmlBody);
		
		try {
			mailerSendWebClient.post()
			                   .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			                   .bodyValue(body)
			                   .retrieve()
			                   .toBodilessEntity()
			                   .block(Duration.ofSeconds(readTimeoutSec + 2L));
			
			log.debug("Mail sent via MailerSend -> to={}, subject={}", helper.maskEmail(to), subject);
			
		} catch (WebClientResponseException wex) {
			int status = wex.getRawStatusCode();
			if (status >= 500) {
				throw HttpServerErrorException.create(
						wex.getStatusCode(),
						wex.getStatusText(),
						wex.getHeaders(),
						wex.getResponseBodyAsByteArray(),
						null
				);
			} else if (status >= 400) {
				throw HttpClientErrorException.create(
						wex.getStatusCode(),
						wex.getStatusText(),
						wex.getHeaders(),
						wex.getResponseBodyAsByteArray(),
						null
				);
			} else {
				throw new SoundConnectException(
						ErrorType.MAIL_QUEUE_ERROR,
						List.of("Unexpected status",
						        "status=" + status,
						        "body=" + safe(wex.getResponseBodyAsString()))
				);
			}
			
		} catch (ResourceAccessException rae) {
			throw rae;
			
		} catch (Exception ex) {
			if (isLikelyTimeoutOrIo(ex)) {
				throw new ResourceAccessException("Mail send I/O/timeout");
			}
			throw new SoundConnectException(
					ErrorType.MAIL_QUEUE_ERROR,
					List.of("MailerSend call failed",
					        "error=" + ex.toString(),
					        "to=" + helper.maskEmail(to),
					        "subject=" + subject)
			);
		}
	}
	
	// ---- helpers ----
	private boolean isLikelyTimeoutOrIo(Throwable t) {
		String name = t.getClass().getName();
		return name.contains("ReadTimeout")
				|| name.contains("WriteTimeout")
				|| name.contains("TimeoutException")
				|| name.contains("ConnectTimeout")
				|| name.contains("IOException")
				|| name.contains("PrematureClose");
	}
	
	private String safe(String s) {
		return s == null ? "<no-body>" : (s.length() > 400 ? s.substring(0, 400) + "..." : s);
	}
}