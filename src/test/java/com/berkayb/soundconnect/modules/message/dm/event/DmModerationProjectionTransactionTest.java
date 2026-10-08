package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.service.AfterCommitDeliveryExecutor;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/** Real commit callbacks and a one-connection pool expose nested connection starvation. */
class DmModerationProjectionTransactionTest {
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    DMMessageRepository messages;
    NotificationService notifications;
    SimpMessagingTemplate messaging;
    NotificationWebSocketService websocket;
    final UUID participant = UUID.randomUUID();

    @BeforeEach void setup() {
        context = new AnnotationConfigApplicationContext(Config.class);
        jdbc = context.getBean(JdbcTemplate.class);
        transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        messages = context.getBean(DMMessageRepository.class);
        notifications = context.getBean(NotificationService.class);
        messaging = context.getBean(SimpMessagingTemplate.class);
        websocket = context.getBean(NotificationWebSocketService.class);
        jdbc.execute("create table fixture_message(id int primary key)");
        jdbc.update("insert into fixture_message values (1)");
        when(messages.countByRecipientIdAndReadAtIsNull(participant))
                .thenAnswer(call -> jdbc.queryForObject("select count(*) from fixture_message", Long.class));
        when(notifications.getUnreadCount(participant)).thenReturn(0L);
    }

    @AfterEach void close() { if (context != null) context.close(); }

    @Test void committedDeleteReleasesItsOnlyConnectionBeforeProjectionAcquiresOne() {
        assertThatCode(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("delete from fixture_message where id=1");
            context.publishEvent(new DmModeratedEvent(Set.of(participant)));
        })).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from fixture_message", Long.class)).isZero();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            verify(messaging).convertAndSend(WebSocketChannels.dmBadge(participant), 0L);
            verify(websocket).sendUnreadBadgeToUser(participant, 0L);
        });
    }

    @Test void rolledBackDeleteDoesNotQueueAnyProjection() throws Exception {
        transaction.executeWithoutResult(status -> {
            jdbc.update("delete from fixture_message where id=1");
            context.publishEvent(new DmModeratedEvent(Set.of(participant)));
            status.setRollbackOnly();
        });
        // A sentinel on the same FIFO executor proves all earlier queued work has drained.
        var drained = new CountDownLatch(1);
        context.getBean(AfterCommitDeliveryExecutor.class).submit(drained::countDown);
        assertThat(drained.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from fixture_message", Long.class)).isOne();
        verifyNoInteractions(messaging, websocket);
    }

    @Test void projectionFailureDoesNotTurnAnAlreadyCommittedDeleteIntoAnApiFailure() {
        when(messages.countByRecipientIdAndReadAtIsNull(participant)).thenThrow(new IllegalStateException("fixture"));
        assertThatCode(() -> transaction.executeWithoutResult(status -> {
            jdbc.update("delete from fixture_message where id=1");
            context.publishEvent(new DmModeratedEvent(Set.of(participant)));
        })).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from fixture_message", Long.class)).isZero();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(messages).countByRecipientIdAndReadAtIsNull(participant));
        verifyNoInteractions(messaging, websocket);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({DmModerationEventDispatcher.class, DmModerationProjection.class, AfterCommitDeliveryExecutor.class})
    static class Config {
        @Bean(destroyMethod = "close") HikariDataSource dataSource() {
            var source = new HikariDataSource();
            source.setJdbcUrl("jdbc:h2:mem:dm_moderation_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            source.setMaximumPoolSize(1);
            source.setConnectionTimeout(300);
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(HikariDataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(HikariDataSource source) {
            return new DataSourceTransactionManager(source);
        }
        @Bean DMMessageRepository messages() { return mock(DMMessageRepository.class); }
        @Bean NotificationService notifications() { return mock(NotificationService.class); }
        @Bean NotificationWebSocketService websocket() { return mock(NotificationWebSocketService.class); }
        @Bean SimpMessagingTemplate messaging() { return mock(SimpMessagingTemplate.class); }
    }
}
