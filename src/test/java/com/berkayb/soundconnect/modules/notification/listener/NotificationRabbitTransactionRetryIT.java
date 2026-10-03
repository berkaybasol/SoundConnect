package com.berkayb.soundconnect.modules.notification.listener;

import com.berkayb.soundconnect.modules.notification.config.NotificationRabbitConfig;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationDeliveryPolicy;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real Rabbit advice -> real @Transactional listener proxy -> disposable H2 transactions. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = {NotificationRabbitConfig.class, NotificationEventListener.class,
        NotificationRabbitTransactionRetryIT.Fixture.class})
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@TestPropertySource(properties = {
        "spring.config.location=classpath:/application-test.yml", "spring.config.import=",
        "spring.autoconfigure.exclude=", "spring.rabbitmq.listener.simple.auto-startup=true",
        "app.messaging.notification.exchange=notification.tx.exchange",
        "app.messaging.notification.queue=notification.tx.queue",
        "app.messaging.notification.routingKey=notification.#",
        "app.messaging.notification.dlxExchange=notification.tx.dlx",
        "app.messaging.notification.dlq=notification.tx.dlq.queue"
})
@DirtiesContext
class NotificationRabbitTransactionRetryIT {
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");
    @DynamicPropertySource static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Fixture {
        @Bean Jackson2JsonMessageConverter jackson2JsonMessageConverter() { return new Jackson2JsonMessageConverter(); }
        @Bean(destroyMethod = "shutdown") EmbeddedDatabase database() {
            var database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
            var jdbc = new JdbcTemplate(database);
            jdbc.execute("create table retry_receipt(source_event_id uuid primary key, recipient_id uuid not null)");
            jdbc.execute("create table retry_inbox(source_event_id uuid primary key, recipient_id uuid not null)");
            return database;
        }
        @Bean JdbcTemplate jdbc(EmbeddedDatabase database) { return new JdbcTemplate(database); }
        @Bean DataSourceTransactionManager transactionManager(EmbeddedDatabase database) { return new DataSourceTransactionManager(database); }
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean NotificationRepository notifications;
    @MockitoBean NotificationReceiptRepository receipts;
    @MockitoBean NotificationBadgeCacheHelper badges;
    @MockitoBean NotificationMapper mapper;
    @MockitoBean NotificationWebSocketService websocket;
    @MockitoBean MailProducer mail;
    @MockitoBean NotificationService service;
    @MockitoBean NotificationDeliveryPolicy policy;

    @Test void eachRetryRollsBackItsReceiptAndInboxBeforeASeparateSuccessfulTransactionCommits() {
        var event = NotificationInboundEvent.builder().eventId(UUID.randomUUID()).recipientId(UUID.randomUUID())
                .type(NotificationType.SOCIAL_NEW_FOLLOWER).title("fixture").message("fixture")
                .payload(Map.of()).emailForce(false).occurredAt(Instant.now()).build();
        var attempts = new AtomicInteger();
        List<Integer> completions = new CopyOnWriteArrayList<>();
        List<Integer> receiptsVisibleBeforeAttempt = new CopyOnWriteArrayList<>();
        when(policy.eligible(any())).thenReturn(true);
        when(receipts.claim(any(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            receiptsVisibleBeforeAttempt.add(jdbc.queryForObject("select count(*) from retry_receipt", Integer.class));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { completions.add(status); }
            });
            return jdbc.update("insert into retry_receipt(source_event_id,recipient_id) values (?,?)", (UUID) call.getArgument(0), (UUID) call.getArgument(1));
        });
        when(notifications.existsBySourceEventId(any())).thenAnswer(call ->
                jdbc.queryForObject("select count(*) from retry_inbox where source_event_id=?", Integer.class, (UUID) call.getArgument(0)) != 0);
        when(notifications.saveAndFlush(any())).thenAnswer(call -> {
            Notification notification = call.getArgument(0);
            jdbc.update("insert into retry_inbox(source_event_id,recipient_id) values (?,?)", notification.getSourceEventId(), notification.getRecipientId());
            if (attempts.incrementAndGet() < 3) throw new TransientDataAccessResourceException("fixture failure after both writes");
            ReflectionTestUtils.setField(notification, "id", UUID.randomUUID());
            return notification;
        });
        rabbit.convertAndSend("notification.tx.exchange", "notification.event", event);
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> assertThat(completions)
                .containsExactly(TransactionSynchronization.STATUS_ROLLED_BACK,
                        TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_COMMITTED));
        assertThat(attempts).hasValue(3);
        assertThat(receiptsVisibleBeforeAttempt).containsExactly(0, 0, 0);
        assertThat(jdbc.queryForObject("select count(*) from retry_receipt", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from retry_inbox", Integer.class)).isEqualTo(1);
        verify(receipts, times(3)).claim(event.eventId(), event.recipientId());
        verify(policy, times(1)).schedule(eq(event), any(), any());
        assertThat(rabbit.receive("notification.tx.dlq.queue", 200)).isNull();
    }
}
