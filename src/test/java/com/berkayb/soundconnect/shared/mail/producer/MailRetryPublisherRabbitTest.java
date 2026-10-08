package com.berkayb.soundconnect.shared.mail.producer;

import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.*;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
class MailRetryPublisherRabbitTest {
    @Container
    static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("soundconnect-rabbitmq:3.13.7").asCompatibleSubstituteFor("rabbitmq"))
            .withLabel("soundconnect.fixture", "bil012-mail-retry").withReuse(false);

    CachingConnectionFactory factory;
    RabbitTemplate shared;
    MailRetryPublisher publisher;
    Connection fixture;
    Channel channel;
    String queue, delayed, direct;

    @BeforeEach
    void setUp() throws Exception {
        assertThat(BROKER.getContainerInfo().getConfig().getLabels())
                .containsEntry("soundconnect.fixture", "bil012-mail-retry");
        factory = new CachingConnectionFactory(BROKER.getHost(), BROKER.getAmqpPort());
        factory.setUsername(BROKER.getAdminUsername());
        factory.setPassword(BROKER.getAdminPassword());
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        shared = new RabbitTemplate(factory);
        shared.setMessageConverter(new Jackson2JsonMessageConverter());
        shared.setMandatory(true);
        String suffix = UUID.randomUUID().toString();
        queue = "bil012.retry.queue." + suffix;
        delayed = "bil012.retry.delay." + suffix;
        direct = "bil012.retry.direct." + suffix;
        fixture = factory.getRabbitConnectionFactory().newConnection();
        channel = fixture.createChannel();
        channel.exchangeDeclare(delayed, "x-delayed-message", true, false,
                Map.of("x-delayed-type", "direct"));
        channel.exchangeDeclare(direct, "direct", true);
        channel.queueDeclare(queue, true, false, false, Map.of());
        channel.queueBind(queue, delayed, "mail.send");
        publisher = new MailRetryPublisher(shared);
        setField("delayedExchange", delayed);
        setField("routingKey", "mail.send");
        setField("confirmTimeoutSec", 2L);
        // This same test compiles against the pre-fix publisher for real broker RED.
        try { setField("mailQueueName", queue); } catch (NoSuchFieldException preFix) { }
        publisher.validateConfiguration();
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) fixture.abort();
        if (factory != null) factory.destroy();
    }

    @Test
    void storageConfirmSchedulesOneDelayedCopyWithIncrementedAttempt() throws Exception {
        assertThatCode(() -> publisher.publishWithDelay(request(), 1_000, 3, "attempt=3"))
                .doesNotThrowAnyException();
        assertThat(channel.basicGet(queue, true)).isNull();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(channel.queueDeclarePassive(queue).getMessageCount()).isEqualTo(1));
        GetResponse delivered = channel.basicGet(queue, true);
        assertThat(delivered.getProps().getHeaders()).containsEntry(MailJobHelper.RETRY_ATTEMPT_HEADER, 3)
                .containsEntry(MailJobHelper.RETRY_MARKER_HEADER, true);
        assertThat(delivered.getProps().getDeliveryMode()).isEqualTo(2);
        assertThat(channel.basicGet(queue, true)).isNull();
    }

    @Test
    void absentDestinationFailsBeforeStoringDelayedWork() throws Exception {
        channel.queueDelete(queue);
        assertThatThrownBy(() -> publisher.publishWithDelay(request(), 1_000, 1, "attempt=1"))
                .isInstanceOf(AmqpException.class);
        channel.queueDeclare(queue, true, false, false, Map.of());
        channel.queueBind(queue, delayed, "mail.send");
        await().during(Duration.ofMillis(1_500)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(channel.queueDeclarePassive(queue).getMessageCount()).isZero());
    }

    @Test
    void storageConfirmationDoesNotPromiseFutureBindingDelivery() throws Exception {
        channel.queueUnbind(queue, delayed, "mail.send");
        assertThatCode(() -> publisher.publishWithDelay(request(), 100, 1, "attempt=1"))
                .doesNotThrowAnyException();
        await().during(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(channel.queueDeclarePassive(queue).getMessageCount()).isZero());
    }

    @Test
    void sharedMandatoryDirectPublishStillReturnsMissingRouteAfterRetry() throws Exception {
        try { publisher.publishWithDelay(request(), 100, 1, "attempt=1"); }
        catch (AmqpException preFix) { /* The separate RED case asserts this defect. */ }
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        shared.convertAndSend(direct, "not.bound", request(), correlation);
        assertThat(correlation.getFuture().get(2, TimeUnit.SECONDS).isAck()).isTrue();
        assertThat(correlation.getReturned()).isNotNull();
        assertThat(correlation.getReturned().getReplyCode()).isEqualTo(312);
    }

    @Test
    void pluginMandatoryReturnCanCoexistWithActualDelayedStorage() throws Exception {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        shared.convertAndSend(delayed, "mail.send", request(), message -> {
            message.getMessageProperties().setHeader("x-delay", 500);
            return message;
        }, correlation);
        assertThat(correlation.getFuture().get(2, TimeUnit.SECONDS).isAck()).isTrue();
        assertThat(correlation.getReturned()).isNotNull();
        assertThat(correlation.getReturned().getReplyCode()).isEqualTo(312);
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() ->
                assertThat(channel.queueDeclarePassive(queue).getMessageCount()).isEqualTo(1));
    }

    private MailSendRequest request() {
        return new MailSendRequest("fixture@example.test", "fixture", "fixture", "fixture",
                MailKind.GENERIC, Map.of());
    }

    private void setField(String name, Object value) throws ReflectiveOperationException {
        Field field = MailRetryPublisher.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(publisher, value);
    }
}
