package com.berkayb.soundconnect.modules.application.mailintent;

import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationAdminMailService;
import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationAdminMailService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Same old public service API on baseline and candidate; real outer PostgreSQL transaction. */
@Testcontainers
class ApplicationMailTransactionBoundaryTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16.4-alpine")
            .withDatabaseName("bil011_boundary").withUsername("bil011").withPassword("bil011");
    AnnotationConfigApplicationContext context;
    TransactionTemplate transaction;
    MailProducer producer;
    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        var tm = new DataSourceTransactionManager(ds);
        transaction = new TransactionTemplate(tm);
        var migration = new FileSystemResource("scripts/db/2026-10-06-application-mail-intents.sql");
        if (migration.exists()) new ResourceDatabasePopulator(migration).execute(ds);
        context = new AnnotationConfigApplicationContext();
        producer = mock(MailProducer.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> tm);
        context.registerBean(NamedParameterJdbcTemplate.class, () -> new NamedParameterJdbcTemplate(ds));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(MailProducer.class, () -> producer);
        // Allows the same test to exercise the old service on baseline, and its real durable dependencies on candidate.
        context.scan("com.berkayb.soundconnect.modules.application.mailintent");
        context.register(VenueApplicationAdminMailService.class, StudioApplicationAdminMailService.class);
        context.refresh();
        ReflectionTestUtils.setField(context.getBean(VenueApplicationAdminMailService.class), "venueApplicationEmails", "admin@example.test");
        ReflectionTestUtils.setField(context.getBean(StudioApplicationAdminMailService.class), "studioApplicationEmails", "admin@example.test");
    }
    @AfterEach void close() { context.close(); }
    @ParameterizedTest @ValueSource(strings = {"venue", "studio", "approved", "rejected"})
    void outerRollbackCannotPublishMail(String purpose) {
        transaction.executeWithoutResult(status -> { send(purpose); status.setRollbackOnly(); });
        verifyNoInteractions(producer);
    }
    @ParameterizedTest @ValueSource(strings = {"venue", "studio", "approved", "rejected"})
    void committingBusinessTransactionDoesNotPerformBrokerIo(String purpose) {
        transaction.executeWithoutResult(status -> send(purpose));
        verifyNoInteractions(producer);
    }
    void send(String purpose) {
        var user = User.builder().id(UUID.randomUUID()).email("applicant@example.test").username("fixture").build();
        if (purpose.equals("venue")) {
            context.getBean(VenueApplicationAdminMailService.class).sendNewApplicationMail(VenueApplication.builder()
                    .id(UUID.randomUUID()).applicant(user).venueName("Fixture").status(ApplicationStatus.PENDING).build());
        } else {
            var app = StudioApplication.builder().id(UUID.randomUUID()).applicant(user).studioName("Fixture")
                    .status(purpose.equals("studio") ? ApplicationStatus.PENDING : purpose.equals("approved")
                            ? ApplicationStatus.APPROVED : ApplicationStatus.REJECTED).build();
            var service = context.getBean(StudioApplicationAdminMailService.class);
            if (purpose.equals("studio")) service.sendNewApplicationMail(app); else service.sendApplicantDecisionMail(app);
        }
    }
}
