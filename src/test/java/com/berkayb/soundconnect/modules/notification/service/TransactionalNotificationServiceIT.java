package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DataJpaTest(properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:transactional-notification-${random.uuid};MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ContextConfiguration(classes = TransactionalNotificationServiceIT.ConfigurationForTest.class)
class TransactionalNotificationServiceIT {
    @Autowired NotificationRepository repository;
    @Autowired TransactionalNotificationService notifications;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationMapper mapper;
    @MockitoBean NotificationBadgeCacheHelper badges;
    @MockitoBean NotificationWebSocketService websocket;
    @MockitoBean NotificationService identityService;
    TransactionTemplate transactions;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        jdbc.execute("create table if not exists notification_domain_test (id uuid primary key)");
        jdbc.update("delete from notification_domain_test");
        reset(mapper, badges, websocket, identityService);
        when(identityService.refreshActorIdentityForDelivery(any())).thenAnswer(call -> call.getArgument(0));
        when(mapper.toDto(any(Notification.class))).thenAnswer(call -> {
            Notification item = call.getArgument(0);
            return new NotificationResponseDto(item.getId(), item.getRecipientId(), item.getType(),
                    item.getTitle(), item.getMessage(), item.isRead(), item.getOccurredAt(), item.getPayload());
        });
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void commitMakesDomainChangeAndInboxDurableBeforeAnyRealtimeDelivery() {
        NotificationInboundEvent event = event();
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into notification_domain_test(id) values (?)", event.eventId());
            notifications.persistInCurrentTransaction(event);
            verifyNoInteractions(mapper, badges, websocket);
        });

        assertThat(domainCount()).isEqualTo(1);
        Notification stored = repository.findBySourceEventId(event.eventId()).orElseThrow();
        assertThat(stored.getRecipientId()).isEqualTo(event.recipientId());
        assertThat(stored.getOccurredAt()).isEqualTo(event.occurredAt());
        assertThat(stored.isRead()).isFalse();
        verify(websocket).sendNotificationToUser(eq(event.recipientId()), any());
        verify(badges).setUnreadWithTtl(event.recipientId(), 1L);
        verifyNoInteractions(identityService);
    }

    @Test
    void rollbackRemovesBothDomainChangeAndInboxAndNeverPushes() {
        NotificationInboundEvent event = event();
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into notification_domain_test(id) values (?)", event.eventId());
            notifications.persistInCurrentTransaction(event);
            status.setRollbackOnly();
        });

        assertThat(domainCount()).isZero();
        assertThat(repository.findBySourceEventId(event.eventId())).isEmpty();
        verifyNoInteractions(mapper, badges, websocket);
    }

    @Test
    void failedInboxValidationRollsBackTheCallingDomainChange() {
        NotificationInboundEvent valid = event();
        NotificationInboundEvent invalid = new NotificationInboundEvent(valid.eventId(), valid.recipientId(),
                valid.type(), "x".repeat(161), valid.message(), valid.payload(), false, valid.occurredAt());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            jdbc.update("insert into notification_domain_test(id) values (?)", valid.eventId());
            notifications.persistInCurrentTransaction(invalid);
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(domainCount()).isZero();
        assertThat(repository.count()).isZero();
        verifyNoInteractions(mapper, badges, websocket);
    }

    @Test
    void stableSourceIdReplayCreatesAndPushesOnlyOneInboxRecord() {
        NotificationInboundEvent event = event();
        transactions.executeWithoutResult(status -> notifications.persistInCurrentTransaction(event));
        transactions.executeWithoutResult(status -> notifications.persistInCurrentTransaction(event));

        assertThat(repository.count()).isEqualTo(1);
        verify(websocket, times(1)).sendNotificationToUser(eq(event.recipientId()), any());
        verify(websocket, times(1)).sendUnreadBadgeToUser(event.recipientId(), 1L);
    }

    @Test
    void projectionFailureAfterCommitCannotLoseDurableInboxOrFailTheDomainCall() {
        NotificationInboundEvent event = event();
        doThrow(new IllegalStateException("Disconnected client"))
                .when(websocket).sendNotificationToUser(eq(event.recipientId()), any());
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(badges).setUnreadWithTtl(event.recipientId(), 1L);
        transactions.executeWithoutResult(status -> {
            jdbc.update("insert into notification_domain_test(id) values (?)", event.eventId());
            notifications.persistInCurrentTransaction(event);
        });

        assertThat(domainCount()).isEqualTo(1);
        assertThat(repository.findBySourceEventId(event.eventId())).isPresent();
        verify(websocket).sendUnreadBadgeToUser(event.recipientId(), 1L);
    }

    @Test
    void missingCallerTransactionIsRejectedBeforeAnInboxWrite() {
        assertThatThrownBy(() -> notifications.persistInCurrentTransaction(event()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(repository.count()).isZero();
        verifyNoInteractions(mapper, badges, websocket);
    }

    private int domainCount() {
        return jdbc.queryForObject("select count(*) from notification_domain_test", Integer.class);
    }

    private static NotificationInboundEvent event() {
        return NotificationInboundEvent.builder().eventId(UUID.randomUUID()).recipientId(UUID.randomUUID())
                .type(NotificationType.BAND_INVITE_RECEIVED).title("Yeni grup daveti")
                .message("Gruba davet edildin.")
                .payload(Map.of("module", "BAND", "action", "INVITE_RECEIVED", "bandId", UUID.randomUUID().toString()))
                .emailForce(false).occurredAt(Instant.parse("2026-09-07T09:00:00Z")).build();
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Notification.class)
    @EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
    @Import({TransactionalNotificationService.class, com.berkayb.soundconnect.support.DeliveryPolicyTestSupport.Config.class})
    static class ConfigurationForTest { }
}
