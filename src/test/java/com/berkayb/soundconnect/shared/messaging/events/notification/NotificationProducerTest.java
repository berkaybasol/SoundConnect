package com.berkayb.soundconnect.shared.messaging.events.notification;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class NotificationProducerTest {
    private RabbitTemplate rabbitTemplate;
    private NotificationProducer producer;
    private NotificationPublisherProperties publisherProperties;

    @BeforeEach
    void setUp() {
        rabbitTemplate = mock(RabbitTemplate.class);
        publisherProperties = new NotificationPublisherProperties();
        publisherProperties.setPublisherConfirmTimeout(Duration.ofSeconds(1));
        producer = new NotificationProducer(rabbitTemplate, publisherProperties);
        ReflectionTestUtils.setField(producer, "exchange", "notification.exchange");
        ReflectionTestUtils.setField(producer, "publishRoutingKey", "notification.event");
        producer.validateConfiguration();
    }

    @Test
    void confirmedAckCompletesSuccessfully() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(),
                anyString(),
                any(NotificationInboundEvent.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        assertThatCode(() -> producer.publishConfirmed(event()))
                .doesNotThrowAnyException();
    }

    @Test
    void brokerNackIsPropagatedToTheDurableCaller() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "queue unavailable"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(),
                anyString(),
                any(NotificationInboundEvent.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        assertThatThrownBy(() -> producer.publishConfirmed(event()))
                .isInstanceOf(AmqpException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    void unroutableReturnIsPropagatedToTheDurableCaller() {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(4);
            correlationData.setReturned(new ReturnedMessage(
                    new Message(new byte[0], new MessageProperties()),
                    312,
                    "NO_ROUTE",
                    "notification.exchange",
                    "notification.event"
            ));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(),
                anyString(),
                any(NotificationInboundEvent.class),
                any(MessagePostProcessor.class),
                any(CorrelationData.class)
        );

        assertThatThrownBy(() -> producer.publishConfirmed(event()))
                .isInstanceOf(AmqpException.class)
                .hasMessageContaining("returned");
    }

    @Test
    void invalidConfirmTimeoutNamesTheSharedConfigurationKey() {
        publisherProperties.setPublisherConfirmTimeout(Duration.ofMillis(999));

        assertThatThrownBy(producer::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("app.messaging.notification.publisher-confirm-timeout must be between 1s and 30s");
    }

    private static NotificationInboundEvent event() {
        return NotificationInboundEvent.builder()
                .eventId(UUID.randomUUID())
                .recipientId(UUID.randomUUID())
                .type(NotificationType.COLLAB_APPLICATION_RECEIVED)
                .title("Yeni basvuru")
                .message("Ilanina yeni bir basvuru geldi.")
                .payload(Map.of("module", "COLLAB", "action", "APPLICATION_RECEIVED"))
                .emailForce(false)
                .occurredAt(Instant.parse("2026-08-11T00:00:00Z"))
                .build();
    }
}
