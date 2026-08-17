package com.berkayb.soundconnect.modules.collab.outbox;

import com.berkayb.soundconnect.modules.collab.event.CollabNotificationEvent;
import com.berkayb.soundconnect.modules.collab.event.CollabNotificationListener;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest(properties = "app.notification.collab-outbox.max-attempts=2")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        CollabNotificationOutboxService.class,
        CollabNotificationDispatchCoordinator.class,
        CollabNotificationListener.class,
        CollabNotificationOutboxTransactionIT.SupportConfiguration.class
})
class CollabNotificationOutboxTransactionIT {

    @Autowired
    private CollabNotificationOutboxRepository repository;

    @Autowired
    private CollabNotificationOutboxService outboxService;

    @Autowired
    private MutableTimeProvider timeProvider;

    @Autowired
    private CollabNotificationOutboxDispatcher dispatcher;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        reset(dispatcher);
        timeProvider.set(Instant.parse("2026-08-11T09:00:00Z"));
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void commitPersistsOutboxInTheDomainTransactionAndSchedulesImmediateDispatch() {
        CollabNotificationEvent event = event();

        transactions.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        CollabNotificationOutbox stored = repository.findById(event.eventId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(CollabNotificationOutboxStatus.PENDING);
        assertThat(stored.getAttemptCount()).isZero();
        assertThat(stored.getRecipientId()).isEqualTo(event.recipientId());
        assertThat(stored.getPayload())
                .containsEntry("module", "COLLAB")
                .containsEntry("action", "APPLICATION_RECEIVED");
        assertThat(String.valueOf(stored.getPayload().get("listingId")))
                .isEqualTo(String.valueOf(event.payload().get("listingId")));
        verify(dispatcher).dispatch(event.eventId());
    }

    @Test
    void rollbackPersistsNoOutboxRowAndDoesNotDispatch() {
        CollabNotificationEvent event = event();

        transactions.executeWithoutResult(status -> {
            eventPublisher.publishEvent(event);
            status.setRollbackOnly();
        });

        assertThat(repository.findById(event.eventId())).isEmpty();
        assertThat(repository.count()).isZero();
        verifyNoInteractions(dispatcher);
    }

    @Test
    void failedAttemptsUseBackoffThenReachObservableDeadLetterState() {
        CollabNotificationEvent event = event();
        transactions.executeWithoutResult(status -> eventPublisher.publishEvent(event));

        CollabNotificationOutboxClaim first = outboxService
                .claim(event.eventId(), "node:first")
                .orElseThrow();
        assertThat(first.attemptCount()).isEqualTo(1);
        assertThat(outboxService.markFailed(first, "java.net.ConnectException: secret host"))
                .isEqualTo(CollabNotificationOutboxService.FailureDisposition.RETRY_SCHEDULED);

        CollabNotificationOutbox retry = repository.findById(event.eventId()).orElseThrow();
        assertThat(retry.getStatus()).isEqualTo(CollabNotificationOutboxStatus.PENDING);
        assertThat(retry.getNextAttemptAt()).isEqualTo(Instant.parse("2026-08-11T09:00:05Z"));
        assertThat(retry.getLastErrorType()).isEqualTo("java.net.ConnectException__secret_host");

        timeProvider.set(Instant.parse("2026-08-11T09:00:05Z"));
        CollabNotificationOutboxClaim second = outboxService
                .claim(event.eventId(), "node:second")
                .orElseThrow();
        assertThat(second.attemptCount()).isEqualTo(2);
        assertThat(outboxService.markFailed(second, "BrokerNack"))
                .isEqualTo(CollabNotificationOutboxService.FailureDisposition.DEAD_LETTER);

        CollabNotificationOutbox dead = repository.findById(event.eventId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(CollabNotificationOutboxStatus.DEAD_LETTER);
        assertThat(dead.getAttemptCount()).isEqualTo(2);
        assertThat(dead.getLeaseOwner()).isNull();
        assertThat(dead.getLeaseUntil()).isNull();
        assertThat(dead.getLastErrorType()).isEqualTo("BrokerNack");
    }

    @Test
    void cleanupRetainsRecentPublishedRowsAndNeverTargetsDeadLetters() {
        CollabNotificationEvent event = event();
        transactions.executeWithoutResult(status -> eventPublisher.publishEvent(event));
        CollabNotificationOutboxClaim claim = outboxService
                .claim(event.eventId(), "node:published")
                .orElseThrow();
        assertThat(outboxService.markPublished(claim)).isTrue();

        timeProvider.set(Instant.parse("2026-08-18T08:59:59Z"));
        assertThat(outboxService.cleanupPublished()).isZero();
        assertThat(repository.findById(event.eventId())).isPresent();

        timeProvider.set(Instant.parse("2026-08-18T09:00:01Z"));
        assertThat(outboxService.cleanupPublished()).isEqualTo(1);
        assertThat(repository.findById(event.eventId())).isEmpty();
    }

    private static CollabNotificationEvent event() {
        return CollabNotificationEvent.create(
                UUID.randomUUID(),
                NotificationType.COLLAB_APPLICATION_RECEIVED,
                "Yeni basvuru",
                "Ilanina yeni bir basvuru geldi.",
                "APPLICATION_RECEIVED",
                Map.of("listingId", UUID.randomUUID()),
                Instant.parse("2026-08-11T08:59:59Z")
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SupportConfiguration {
        @Bean
        CollabNotificationOutboxProperties collabNotificationOutboxProperties() {
            CollabNotificationOutboxProperties properties = new CollabNotificationOutboxProperties();
            properties.setMaxAttempts(2);
            return properties;
        }

        @Bean
        MutableTimeProvider collabNotificationOutboxTimeProvider() {
            return new MutableTimeProvider();
        }

        @Bean
        CollabNotificationOutboxDispatcher collabNotificationOutboxDispatcher() {
            return mock(CollabNotificationOutboxDispatcher.class);
        }

        @Bean(name = CollabNotificationOutboxConfiguration.EXECUTOR_BEAN)
        Executor collabNotificationOutboxExecutor() {
            return Runnable::run;
        }
    }

    static class MutableTimeProvider extends CollabNotificationOutboxTimeProvider {
        private Instant now = Instant.EPOCH;

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }
    }
}
