package com.berkayb.soundconnect.modules.event.performer.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
		EventPerformerNotificationOutboxService.class,
		EventPerformerNotificationOutboxPublisher.class,
		EventPerformerNotificationDispatchCoordinator.class,
		EventPerformerNotificationOutboxTransactionIT.SupportConfiguration.class
})
class EventPerformerNotificationOutboxTransactionIT {
	@Autowired
	private EventPerformerNotificationOutboxRepository repository;

	@Autowired
	private EventPerformerNotificationOutboxPublisher publisher;

	@Autowired
	private EventPerformerNotificationOutboxService service;

	@Autowired
	private EventPerformerNotificationOutboxProperties properties;

	@Autowired
	private EventPerformerNotificationOutboxDispatcher dispatcher;

	@Autowired
	private MutableTimeProvider timeProvider;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactions;

	@BeforeEach
	void setUp() {
		repository.deleteAll();
		reset(dispatcher);
		timeProvider.set(Instant.parse("2026-09-04T12:00:00Z"));
		transactions = new TransactionTemplate(transactionManager);
		properties.setMaxAttempts(8);
	}

	@Test
	void commitPersistsOutboxInTheDomainTransactionThenSchedulesFastDispatch() {
		NotificationInboundEvent notification = notification();

		transactions.executeWithoutResult(status -> publisher.enqueueAll(List.of(notification)));

		EventPerformerNotificationOutbox stored = repository
				.findById(notification.eventId())
				.orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(EventPerformerNotificationOutboxStatus.PENDING);
		assertThat(stored.getAttemptCount()).isZero();
		assertThat(stored.getRecipientId()).isEqualTo(notification.recipientId());
		assertThat(stored.getPayload())
				.containsEntry("module", "EVENT_PERFORMER")
				.containsEntry("action", "APPROVAL_REQUESTED");
		verify(dispatcher).dispatch(notification.eventId());
	}

	@Test
	void rollbackPersistsNothingAndNeverDispatches() {
		NotificationInboundEvent notification = notification();

		transactions.executeWithoutResult(status -> {
			publisher.enqueueAll(List.of(notification));
			status.setRollbackOnly();
		});

		assertThat(repository.findById(notification.eventId())).isEmpty();
		assertThat(repository.count()).isZero();
		verifyNoInteractions(dispatcher);
	}

	@Test
	void expiredLeaseCannotAcknowledgeOrRescheduleAClaimRecoveredByAnotherWorker() {
		NotificationInboundEvent notification = notification();
		transactions.executeWithoutResult(status -> publisher.enqueueAll(List.of(notification)));
		var first = service.claim(notification.eventId(), "node:first").orElseThrow();
		assertThat(first.attemptCount()).isEqualTo(1);
		assertThat(service.claim(notification.eventId(), "node:early")).isEmpty();
		timeProvider.set(Instant.parse("2026-09-04T12:00:00Z").plus(properties.getLeaseDuration()));
		var recovered = service.claim(notification.eventId(), "node:recovered").orElseThrow();
		assertThat(recovered.attemptCount()).isEqualTo(2);
		assertThat(service.markPublished(first)).isFalse();
		assertThat(service.markFailed(first, "StaleFailure"))
				.isEqualTo(EventPerformerNotificationOutboxService.FailureDisposition.LEASE_LOST);
		assertThat(service.markPublished(recovered)).isTrue();
		assertThat(service.claim(notification.eventId(), "node:late")).isEmpty();
		EventPerformerNotificationOutbox stored = repository.findById(notification.eventId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(EventPerformerNotificationOutboxStatus.PUBLISHED);
		assertThat(stored.getLeaseOwner()).isNull();
		assertThat(stored.getLeaseUntil()).isNull();
	}

	@Test
	void backoffAndAttemptLimitAreEnforcedByPersistedOutboxState() {
		properties.setMaxAttempts(2);
		NotificationInboundEvent notification = notification();
		transactions.executeWithoutResult(status -> publisher.enqueueAll(List.of(notification)));
		var first = service.claim(notification.eventId(), "node:first").orElseThrow();
		assertThat(service.markFailed(first, "BrokerNack"))
				.isEqualTo(EventPerformerNotificationOutboxService.FailureDisposition.RETRY_SCHEDULED);
		assertThat(service.claim(notification.eventId(), "node:tooSoon")).isEmpty();
		timeProvider.set(Instant.parse("2026-09-04T12:00:00Z").plus(properties.getRetryInitialDelay()));
		var second = service.claim(notification.eventId(), "node:second").orElseThrow();
		assertThat(service.markFailed(second, "BrokerNack"))
				.isEqualTo(EventPerformerNotificationOutboxService.FailureDisposition.DEAD_LETTER);
		assertThat(service.claim(notification.eventId(), "node:afterLimit")).isEmpty();
		assertThat(repository.findById(notification.eventId()).orElseThrow().getStatus())
				.isEqualTo(EventPerformerNotificationOutboxStatus.DEAD_LETTER);
	}

	@Test
	void immediateDispatchFailureCannotUndoCommitOrLoseDurableRecovery() {
		NotificationInboundEvent notification = notification();
		doThrow(new IllegalStateException("Simulated dispatcher outage")).when(dispatcher).dispatch(notification.eventId());
		transactions.executeWithoutResult(status -> publisher.enqueueAll(List.of(notification)));
		assertThat(repository.findById(notification.eventId()).orElseThrow().getStatus())
				.isEqualTo(EventPerformerNotificationOutboxStatus.PENDING);
		assertThat(service.claim(notification.eventId(), "scheduler:recovery")).isPresent();
	}

	private static NotificationInboundEvent notification() {
		return NotificationInboundEvent.builder()
				.eventId(UUID.randomUUID())
				.recipientId(UUID.randomUUID())
				.type(NotificationType.EVENT_PERFORMER_APPROVAL_REQUESTED)
				.title("Etkinlik katılım onayı")
				.message("Bir mekân seni etkinliğe eklemek istiyor.")
				.payload(Map.of(
						"module", "EVENT_PERFORMER",
						"action", "APPROVAL_REQUESTED",
						"eventId", UUID.randomUUID().toString()
				))
				.emailForce(false)
				.occurredAt(Instant.parse("2026-09-04T11:59:59Z"))
				.build();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SupportConfiguration {
		@Bean
		EventPerformerNotificationOutboxProperties eventPerformerNotificationOutboxProperties() {
			return new EventPerformerNotificationOutboxProperties();
		}

		@Bean
		MutableTimeProvider eventPerformerNotificationOutboxTimeProvider() {
			return new MutableTimeProvider();
		}

		@Bean
		EventPerformerNotificationOutboxDispatcher eventPerformerNotificationOutboxDispatcher() {
			return mock(EventPerformerNotificationOutboxDispatcher.class);
		}

		@Bean(name = EventPerformerNotificationOutboxConfiguration.EXECUTOR_BEAN)
		Executor eventPerformerNotificationOutboxExecutor() {
			return Runnable::run;
		}
	}

	static class MutableTimeProvider extends EventPerformerNotificationOutboxTimeProvider {
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
