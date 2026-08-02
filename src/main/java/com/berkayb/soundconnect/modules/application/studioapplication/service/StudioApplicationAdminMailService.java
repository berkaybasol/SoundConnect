package com.berkayb.soundconnect.modules.application.studioapplication.service;

import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudioApplicationAdminMailService {

	private static final DateTimeFormatter DATE_TIME_FORMATTER =
			DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("tr-TR"));

	private final MailProducer mailProducer;

	@Value("${soundconnect.admin-notifications.studio-application-emails:backstage@soundconnect.com.tr,berkay@soundconnect.com.tr}")
	private String studioApplicationEmails;

	public void sendNewApplicationMail(StudioApplication application) {
		List<String> recipients = parseRecipients();
		if (recipients.isEmpty()) {
			log.warn("Studio application admin mail skipped: no recipients configured. applicationId={}",
					application.getId());
			return;
		}

		for (String recipient : recipients) {
			try {
				mailProducer.send(buildRequest(recipient, application));
			} catch (Exception exception) {
				log.warn("Studio application admin mail queue failed. to={}, applicationId={}, err={}",
						recipient, application.getId(), exception.toString());
			}
		}
	}

	private MailSendRequest buildRequest(String recipient, StudioApplication application) {
		String studioName = safe(application.getStudioName(), "Stüdyo");
		return new MailSendRequest(
				recipient,
				"Yeni Stüdyo Başvurusu: " + studioName,
				null,
				buildTextBody(application),
				MailKind.STUDIO_APPLICATION_ADMIN,
				buildParams(application)
		);
	}

	private String buildTextBody(StudioApplication application) {
		User applicant = application.getApplicant();
		return """
				Yeni bir stüdyo başvurusu alındı.

				Başvuru Bilgileri
				- Başvuru ID: %s
				- Başvuru Tarihi: %s
				- Durum: %s

				Kullanıcı Bilgileri
				- Kullanıcı ID: %s
				- Kullanıcı Adı: %s
				- E-posta: %s

				Stüdyo Bilgileri
				- Stüdyo Adı: %s
				- Adres: %s
				- Telefon: %s
				- Şehir: %s
				- İlçe: %s
				- Mahalle: %s

				SoundConnect backend tarafından otomatik gönderildi.
				""".formatted(
				application.getId(),
				application.getApplicationDate() == null
						? "-"
						: application.getApplicationDate().format(DATE_TIME_FORMATTER),
				application.getStatus(),
				applicant == null ? "-" : applicant.getId(),
				applicant == null ? "-" : safe(applicant.getUsername(), "-"),
				applicant == null ? "-" : safe(applicant.getEmail(), "-"),
				safe(application.getStudioName(), "-"),
				safe(application.getStudioAddress(), "-"),
				safe(application.getPhone(), "-"),
				application.getCity() == null ? "-" : safe(application.getCity().getName(), "-"),
				application.getDistrict() == null ? "-" : safe(application.getDistrict().getName(), "-"),
				application.getNeighborhood() == null ? "-" : safe(application.getNeighborhood().getName(), "-")
		);
	}

	private Map<String, Object> buildParams(StudioApplication application) {
		User applicant = application.getApplicant();
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("applicationType", "STUDIO");
		params.put("applicationId", stringValue(application.getId()));
		params.put("status", stringValue(application.getStatus()));
		params.put("applicationDate", stringValue(application.getApplicationDate()));
		params.put("applicantId", applicant == null ? "" : stringValue(applicant.getId()));
		params.put("applicantUsername", applicant == null ? "" : safe(applicant.getUsername(), ""));
		params.put("applicantEmail", applicant == null ? "" : safe(applicant.getEmail(), ""));
		params.put("studioName", safe(application.getStudioName(), ""));
		params.put("studioAddress", safe(application.getStudioAddress(), ""));
		params.put("studioPhone", safe(application.getPhone(), ""));
		params.put("city", application.getCity() == null ? "" : safe(application.getCity().getName(), ""));
		params.put("district", application.getDistrict() == null ? "" : safe(application.getDistrict().getName(), ""));
		params.put("neighborhood",
				application.getNeighborhood() == null ? "" : safe(application.getNeighborhood().getName(), ""));
		return params;
	}

	private List<String> parseRecipients() {
		return Arrays.stream(studioApplicationEmails.split(","))
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.distinct()
				.toList();
	}

	private String safe(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private String stringValue(Object value) {
		return value == null ? "" : value.toString();
	}
}
