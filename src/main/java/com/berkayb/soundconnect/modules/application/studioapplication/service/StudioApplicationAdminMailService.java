package com.berkayb.soundconnect.modules.application.studioapplication.service;

import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudioApplicationAdminMailService {

	private static final DateTimeFormatter DATE_TIME_FORMATTER =
			DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("tr-TR"));
	private static final ZoneId ISTANBUL = ZoneId.of("Europe/Istanbul");

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

		List<MailSendRequest> requests = recipients.stream()
				.map(recipient -> buildRequest(recipient, application))
				.toList();
		publishAfterCommit(requests, application.getId(), "admin");
	}

	/**
	 * Queues the applicant-facing terminal decision only after the surrounding
	 * approval/rejection transaction commits. The service receives an already
	 * populated application so the immutable Rabbit payload is captured before
	 * the persistence context closes.
	 */
	public void sendApplicantDecisionMail(StudioApplication application) {
		if (application == null) {
			log.warn("Studio application decision mail skipped: application is missing");
			return;
		}
		if (application.getStatus() != ApplicationStatus.APPROVED
				&& application.getStatus() != ApplicationStatus.REJECTED) {
			log.warn("Studio application decision mail skipped: non-terminal status. applicationId={}",
					application.getId());
			return;
		}
		User applicant = application.getApplicant();
		String recipient = applicant == null ? "" : safe(applicant.getEmail(), "");
		if (recipient.isBlank()) {
			log.warn("Studio application decision mail skipped: applicant email is missing. applicationId={}",
					application.getId());
			return;
		}

		MailSendRequest request = buildDecisionRequest(recipient, application);
		publishAfterCommit(List.of(request), application.getId(), "applicant-decision");
	}

	private void publishAfterCommit(
			List<MailSendRequest> requests,
			UUID applicationId,
			String mailPurpose
	) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			dispatch(requests, applicationId, mailPurpose);
			return;
		}

		// Creation and terminal decisions can join wider transactions. Capture an
		// immutable payload while lazy relations are available, but publish it only
		// after the database changes are durable.
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				dispatch(requests, applicationId, mailPurpose);
			}
		});
	}

	private void dispatch(
			List<MailSendRequest> requests,
			UUID applicationId,
			String mailPurpose
	) {
		for (MailSendRequest request : requests) {
			try {
				mailProducer.send(request);
			} catch (Exception exception) {
				// Do not log the recipient, reason, body, provider response, or any
				// other applicant PII. Rabbit/MailSender own masked delivery logging.
				log.warn("Studio application {} mail queue failed. applicationId={}, exceptionType={}",
						mailPurpose, applicationId, exception.getClass().getSimpleName());
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

	private MailSendRequest buildDecisionRequest(String recipient, StudioApplication application) {
		boolean approved = application.getStatus() == ApplicationStatus.APPROVED;
		String studioName = safe(application.getStudioName(), "Stüdyo");
		String subject = approved
				? "Stüdyo Başvurunuz Onaylandı: " + studioName
				: "Stüdyo Başvurunuz Hakkında: " + studioName;
		return new MailSendRequest(
				recipient,
				subject,
				null,
				buildDecisionTextBody(application, approved),
				MailKind.STUDIO_APPLICATION_DECISION,
				buildDecisionParams(application)
		);
	}

	private String buildDecisionTextBody(StudioApplication application, boolean approved) {
		User applicant = application.getApplicant();
		String greetingName = applicant == null ? "" : safe(applicant.getUsername(), "");
		String greeting = greetingName.isBlank() ? "Merhaba," : "Merhaba " + greetingName + ",";
		String studioName = safe(application.getStudioName(), "Stüdyo");
		String decisionDate = formatDate(application.getDecisionDate());
		if (approved) {
			return """
					%s

					"%s" adlı stüdyo başvurunuz onaylandı.

					- Başvuru ID: %s
					- Karar Tarihi (TSİ): %s

					SoundConnect'e kullanıcı adınız ve şifrenizle giriş yaparak stüdyo profilinizi yönetebilirsiniz.
					""".formatted(
					greeting,
					studioName,
					application.getId(),
					decisionDate
			);
		}

		return """
				%s

				"%s" adlı stüdyo başvurunuz değerlendirme sonucunda onaylanmadı.

				- Başvuru ID: %s
				- Karar Tarihi (TSİ): %s
				- Red Nedeni: %s

				Bilgi almak veya itiraz etmek için destek ekibimizle iletişime geçebilirsiniz.
				""".formatted(
				greeting,
				studioName,
				application.getId(),
				decisionDate,
				safe(application.getRejectionReason(), "Belirtilmedi.")
		);
	}

	private String buildTextBody(StudioApplication application) {
		User applicant = application.getApplicant();
		return """
				Yeni bir stüdyo başvurusu alındı.

				Başvuru Bilgileri
				- Başvuru ID: %s
				- Başvuru Tarihi (TSİ): %s
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
				formatApplicationDate(application),
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
		params.put("applicationDate", formatApplicationDate(application));
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

	private Map<String, Object> buildDecisionParams(StudioApplication application) {
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("applicationType", "STUDIO");
		params.put("applicationId", stringValue(application.getId()));
		params.put("status", stringValue(application.getStatus()));
		params.put("decisionDate", formatDate(application.getDecisionDate()));
		params.put("studioName", safe(application.getStudioName(), ""));
		params.put("rejectionReason", safe(application.getRejectionReason(), ""));
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

	private String formatApplicationDate(StudioApplication application) {
		return formatDate(application.getApplicationDate());
	}

	private String formatDate(LocalDateTime value) {
		if (value == null) return "-";
		return value
				.toInstant(ZoneOffset.UTC)
				.atZone(ISTANBUL)
				.format(DATE_TIME_FORMATTER);
	}
}
