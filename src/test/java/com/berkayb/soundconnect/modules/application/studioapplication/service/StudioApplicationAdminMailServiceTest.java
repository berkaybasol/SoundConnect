package com.berkayb.soundconnect.modules.application.studioapplication.service;

import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@org.testcontainers.junit.jupiter.Testcontainers
class StudioApplicationAdminMailServiceTest {

	@Mock MailProducer mailProducer;
	StudioApplicationAdminMailService service;
    @org.testcontainers.junit.jupiter.Container
    static final org.testcontainers.containers.PostgreSQLContainer<?> PG = new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("bil011_studio_mail").withUsername("bil011").withPassword("bil011");
    org.springframework.transaction.support.TransactionTemplate transaction;
    com.berkayb.soundconnect.modules.application.mailintent.ApplicationMailDispatcher dispatcher;
    org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.junit.jupiter.api.BeforeEach void realTransactionFixture() {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.FileSystemResource(
                "scripts/db/2026-10-06-application-mail-intents.sql")).execute(ds);
        jdbc.execute("DELETE FROM tbl_application_mail_intent"); // This test's disposable PG only.
        var tm = new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        transaction = new org.springframework.transaction.support.TransactionTemplate(tm);
        var properties = new com.berkayb.soundconnect.modules.application.mailintent.ApplicationMailProperties();
        var store = new com.berkayb.soundconnect.modules.application.mailintent.ApplicationMailIntentStore(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(ds),new com.fasterxml.jackson.databind.ObjectMapper(),tm,properties);
        service = new StudioApplicationAdminMailService(store);
        dispatcher = new com.berkayb.soundconnect.modules.application.mailintent.ApplicationMailDispatcher(store,mailProducer,properties,Runnable::run);
    }
    void commitAndDispatch(Runnable send) { transaction.executeWithoutResult(tx -> send.run()); dispatcher.dispatchBatch(); }
    long intents() { return jdbc.queryForObject("SELECT count(*) FROM tbl_application_mail_intent",Long.class); }


	@AfterEach
	void clearTransactionSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void sendsDistinctStudioSpecificNotificationsWithCorrectApplicationData() {
		ReflectionTestUtils.setField(
				service,
				"studioApplicationEmails",
				"ops@example.com, ops@example.com, admin@example.com"
		);
		StudioApplication application = application();

		commitAndDispatch(() -> service.sendNewApplicationMail(application));

		ArgumentCaptor<MailSendRequest> captor = ArgumentCaptor.forClass(MailSendRequest.class);
		verify(mailProducer, times(2)).send(captor.capture());
		List<MailSendRequest> requests = captor.getAllValues();
		assertThat(requests).extracting(MailSendRequest::to)
				.containsExactlyInAnyOrder("ops@example.com", "admin@example.com");
		MailSendRequest request = requests.getFirst();
		assertThat(request.kind()).isEqualTo(MailKind.STUDIO_APPLICATION_ADMIN);
		assertThat(request.subject()).isEqualTo("Yeni Stüdyo Başvurusu: Devo Studio");
		assertThat(request.textBody())
				.contains("Yeni bir stüdyo başvurusu alındı.")
				.contains("- Başvuru Tarihi (TSİ): 24.07.2026 04:30")
				.contains("- Stüdyo Adı: Devo Studio")
				.contains("- Adres: Moda Caddesi")
				.contains("- Telefon: 05551234567")
				.contains("- Şehir: İstanbul")
				.contains("- İlçe: Kadıköy")
				.contains("- Mahalle: Moda")
				.doesNotContain("Yeni bir mekan başvurusu");
		assertThat(request.params())
				.containsEntry("applicationType", "STUDIO")
				.containsEntry("studioName", "Devo Studio");
	}

	@Test
	void defersNotificationUntilTheCreatingTransactionCommits() {
        ReflectionTestUtils.setField(service,"studioApplicationEmails","ops@example.com");
        transaction.executeWithoutResult(tx -> {
            service.sendNewApplicationMail(application());
            verify(mailProducer,never()).send(org.mockito.ArgumentMatchers.any());
            assertThat(intents()).isEqualTo(1);
        });
        verify(mailProducer,never()).send(org.mockito.ArgumentMatchers.any());
        dispatcher.dispatchBatch();
        verify(mailProducer).send(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void doesNotNotifyForARolledBackApplication() {
        ReflectionTestUtils.setField(service,"studioApplicationEmails","ops@example.com");
        transaction.executeWithoutResult(tx -> { service.sendNewApplicationMail(application()); tx.setRollbackOnly(); });
        assertThat(intents()).isZero();
        dispatcher.dispatchBatch();
        verify(mailProducer,never()).send(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void sendsApprovedDecisionToApplicantWithStudioSpecificCopy() {
		StudioApplication application = decidedApplication(ApplicationStatus.APPROVED);

		commitAndDispatch(() -> service.sendApplicantDecisionMail(application));

		ArgumentCaptor<MailSendRequest> captor = ArgumentCaptor.forClass(MailSendRequest.class);
		verify(mailProducer).send(captor.capture());
		MailSendRequest request = captor.getValue();
		assertThat(request.to()).isEqualTo("owner@example.com");
		assertThat(request.kind()).isEqualTo(MailKind.STUDIO_APPLICATION_DECISION);
		assertThat(request.subject()).isEqualTo("Stüdyo Başvurunuz Onaylandı: Devo Studio");
		assertThat(request.textBody())
				.contains("\"Devo Studio\" adlı stüdyo başvurunuz onaylandı.")
				.contains("- Karar Tarihi (TSİ): 03.08.2026 12:15")
				.contains("kullanıcı adınız ve şifrenizle giriş")
				.doesNotContain("Red Nedeni");
		assertThat(request.params())
				.containsEntry("applicationType", "STUDIO")
				.containsEntry("applicationId", application.getId().toString())
				.containsEntry("status", "APPROVED")
				.containsEntry("rejectionReason", "");
	}

	@Test
	void sendsRejectedDecisionWithTheNormalizedReason() {
		StudioApplication application = decidedApplication(ApplicationStatus.REJECTED);
		application.setRejectionReason("Belgeler doğrulanamadı.");

		commitAndDispatch(() -> service.sendApplicantDecisionMail(application));

		ArgumentCaptor<MailSendRequest> captor = ArgumentCaptor.forClass(MailSendRequest.class);
		verify(mailProducer).send(captor.capture());
		MailSendRequest request = captor.getValue();
		assertThat(request.kind()).isEqualTo(MailKind.STUDIO_APPLICATION_DECISION);
		assertThat(request.subject()).isEqualTo("Stüdyo Başvurunuz Hakkında: Devo Studio");
		assertThat(request.textBody())
				.contains("değerlendirme sonucunda onaylanmadı")
				.contains("- Red Nedeni: Belgeler doğrulanamadı.");
		assertThat(request.params())
				.containsEntry("status", "REJECTED")
				.containsEntry("rejectionReason", "Belgeler doğrulanamadı.");
	}

	@Test
	void defersApplicantDecisionUntilCommit() {
        transaction.executeWithoutResult(tx -> {
            service.sendApplicantDecisionMail(decidedApplication(ApplicationStatus.APPROVED));
            verify(mailProducer,never()).send(org.mockito.ArgumentMatchers.any());
            assertThat(intents()).isEqualTo(1);
        });
        verify(mailProducer,never()).send(org.mockito.ArgumentMatchers.any());
        dispatcher.dispatchBatch();
        verify(mailProducer).send(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void doesNotSendApplicantDecisionWhenTransactionRollsBack() {
        transaction.executeWithoutResult(tx -> { service.sendApplicantDecisionMail(decidedApplication(ApplicationStatus.REJECTED)); tx.setRollbackOnly(); });
        assertThat(intents()).isZero();
        dispatcher.dispatchBatch();
        verify(mailProducer,never()).send(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void ignoresNonTerminalApplicationsAndMissingApplicantEmail() {
		StudioApplication pending = application();
		StudioApplication approvedWithoutEmail = decidedApplication(ApplicationStatus.APPROVED);
		approvedWithoutEmail.getApplicant().setEmail("  ");

		service.sendApplicantDecisionMail(pending);
		service.sendApplicantDecisionMail(approvedWithoutEmail);

		verify(mailProducer, never()).send(org.mockito.ArgumentMatchers.any());
	}

	private StudioApplication decidedApplication(ApplicationStatus status) {
		StudioApplication application = application();
		application.setStatus(status);
		application.setDecisionDate(LocalDateTime.of(2026, 8, 3, 9, 15));
		return application;
	}

	private StudioApplication application() {
		City city = City.builder().id(UUID.randomUUID()).name("İstanbul").build();
		District district = District.builder().id(UUID.randomUUID()).name("Kadıköy").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder()
				.id(UUID.randomUUID()).name("Moda").district(district).build();
		User applicant = User.builder()
				.id(UUID.randomUUID())
				.username("studio-owner")
				.email("owner@example.com")
				.build();
		return StudioApplication.builder()
				.id(UUID.randomUUID())
				.applicant(applicant)
				.studioName("Devo Studio")
				.studioAddress("Moda Caddesi")
				.phone("05551234567")
				.city(city)
				.district(district)
				.neighborhood(neighborhood)
				.status(ApplicationStatus.PENDING)
				.applicationDate(LocalDateTime.of(2026, 7, 24, 1, 30))
				.build();
	}
}
