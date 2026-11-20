package com.berkayb.soundconnect.shared.mail.producer;

import com.berkayb.soundconnect.shared.config.MailQueueConfig;
import com.berkayb.soundconnect.shared.config.MailRetryTopology;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.GetResponse;
import com.github.dockerjava.api.model.ExposedPort;
import org.junit.jupiter.api.*;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.builder.Transferable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Owned plugin-free broker only. No SMTP or external provider is invoked. */
@Testcontainers
class MailRetryPublisherRabbitTest {
    @Container static final RabbitMQContainer BROKER = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:4.3.6-management"))
            .withLabel("soundconnect.fixture", "stage01-native-mail-retry").withReuse(false)
            .withCopyToContainer(Transferable.of("vm_memory_high_watermark.absolute = 256MiB\n"),
                    "/etc/rabbitmq/conf.d/99-mail-fixture.conf")
            .withCreateContainerCmdModifier(command -> command.getHostConfig()
                    .withMemory(512L * 1024 * 1024).withMemorySwap(512L * 1024 * 1024));

    CachingConnectionFactory factory;
    RabbitTemplate shared;
    MailRetryPublisher publisher;
    Connection fixture;
    Channel channel;
    int connectedPort;
    String queue, direct, dlx, dlq;
    MailQueueConfig topology;
    final List<String> ownedQueues = new ArrayList<>();

    @BeforeEach void setUp() throws Exception {
        assertThat(BROKER.getContainerInfo().getConfig().getLabels())
                .containsEntry("soundconnect.fixture", "stage01-native-mail-retry");
        connect();
        queue = "stage01.retry." + UUID.randomUUID();
        direct = queue + ".direct"; dlx = queue + ".dlx"; dlq = queue + ".dlq";
        topology = new MailQueueConfig();
        ReflectionTestUtils.setField(topology, "mailQueueName", queue);
        ReflectionTestUtils.setField(topology, "mailQueueType", "quorum");
        ReflectionTestUtils.setField(topology, "mailExchangeName", direct);
        ReflectionTestUtils.setField(topology, "mailRoutingKey", "mail.send");
        ReflectionTestUtils.setField(topology, "mailDlxExchangeName", dlx);
        ReflectionTestUtils.setField(topology, "mailDlqName", dlq);
        ReflectionTestUtils.setField(topology, "mailMessageTtlMs", 120_000L);
        channel.exchangeDeclare(direct, "direct", true);
        channel.exchangeDeclare(dlx, "direct", true);
        declare(topology.mailQueue()); declare(topology.mailDlqQueue());
        channel.queueBind(queue, direct, "mail.send"); channel.queueBind(dlq, dlx, dlq);
        for (Queue retry : topology.mailRetryQueues().getDeclarablesByType(Queue.class)) declare(retry);
        configurePublisher();
    }

    private void connect() throws Exception {
        // Docker Desktop can assign a new ephemeral host port on container restart.
        // Testcontainers' initial inspection is cached, so read the current binding.
        var current = BROKER.getDockerClient().inspectContainerCmd(BROKER.getContainerId()).exec();
        var bindings = current.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(5672));
        assertThat(bindings).isNotEmpty();
        connectedPort = Integer.parseInt(bindings[0].getHostPortSpec());
        factory = new CachingConnectionFactory(BROKER.getHost(), connectedPort);
        factory.setUsername(BROKER.getAdminUsername()); factory.setPassword(BROKER.getAdminPassword());
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        shared = new RabbitTemplate(factory);
        shared.setMessageConverter(new Jackson2JsonMessageConverter()); shared.setMandatory(true);
        fixture = factory.getRabbitConnectionFactory().newConnection();
        channel = fixture.createChannel();
    }

    private void configurePublisher() {
        publisher = new MailRetryPublisher(shared);
        ReflectionTestUtils.setField(publisher, "mailQueueName", queue);
        ReflectionTestUtils.setField(publisher, "confirmTimeoutSec", 3L);
        publisher.validateConfiguration();
    }

    private void declare(Queue definition) throws Exception {
        ownedQueues.add(definition.getName());
        channel.queueDeclare(definition.getName(), true, false, false, definition.getArguments());
    }

    @AfterEach void tearDown() throws Exception {
        try {
            if (fixture != null && fixture.isOpen()) {
                try (Channel cleanup = fixture.createChannel()) {
                    for (String name : ownedQueues) cleanup.queueDelete(name);
                    if (direct != null) cleanup.exchangeDelete(direct);
                    if (dlx != null) cleanup.exchangeDelete(dlx);
                }
            }
        } finally {
            if (fixture != null) fixture.abort();
            if (factory != null) factory.destroy();
        }
    }

    @Test void confirmedPersistentRetryArrivesNoEarlierThanItsBucketWithPrivateHeaders() throws Exception {
        long started = System.nanoTime();
        publisher.publishWithDelay(request(), 1_000, 3, "SYNTHETIC_SECRET_NOTE");
        assertThat(channel.basicGet(queue, true)).isNull();
        GetResponse delivered = receive(queue);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(950));
        assertThat(delivered.getProps().getHeaders()).containsEntry(MailJobHelper.RETRY_ATTEMPT_HEADER, 3)
                .containsEntry(MailJobHelper.RETRY_MARKER_HEADER, true)
                .doesNotContainKeys("x-delay", "sc-retry-note");
        assertThat(delivered.getProps().getDeliveryMode()).isEqualTo(2);
        assertThat(channel.basicGet(queue, true)).isNull();
        System.out.println("MAIL_BROKER delayed: persistent=true retryAttempt=3 noEarlyDelivery=true rawNote=false");
    }

    @Test void absentRetryQueueReturnsFailureRatherThanAcknowledgingSource() throws Exception {
        channel.queueDelete(MailRetryTopology.queueName(queue, 1_000));
        assertThatThrownBy(() -> publisher.publishWithDelay(request(), 1_000, 1, "attempt=1"))
                .isInstanceOf(AmqpException.class);
        assertThat(channel.basicGet(queue, true)).isNull();
    }

    @Test void absentMainDestinationFailsBeforeStoringRetry() throws Exception {
        channel.queueDelete(queue);
        assertThatThrownBy(() -> publisher.publishWithDelay(request(), 1_000, 1, "attempt=1"))
                .isInstanceOf(AmqpException.class);
        assertThat(channel.queueDeclarePassive(MailRetryTopology.queueName(queue, 1_000)).getMessageCount()).isZero();
    }

    @Test void missingFutureBindingRetainsExpiredRetryUntilBindingRecovers() throws Exception {
        channel.queueUnbind(queue, direct, "mail.send");
        publisher.publishWithDelay(request(), 100, 2, "attempt=2");
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(channel.queueDeclarePassive(queue).getMessageCount()).isZero());
        channel.queueBind(queue, direct, "mail.send");
        // Broker 4.3.6 retries unavailable DLX routes every 180s by default.
        // Preserve that real setting; this assertion is eventual recovery, not a latency SLA.
        assertThat(receive(queue, Duration.ofSeconds(210)).getProps().getHeaders())
                .containsEntry(MailJobHelper.RETRY_ATTEMPT_HEADER, 2);
        System.out.println("MAIL_BROKER routeRecovery: retryRetained=true deliveredAfterBindingRestore=true");
    }

    @Test void longerRetryCannotBlockTheShortLane() throws Exception {
        publisher.publishWithDelay(request(), 30_000, 3, "long");
        publisher.publishWithDelay(request(), 100, 1, "short");
        GetResponse delivered = receive(queue);
        assertThat(delivered.getProps().getHeaders()).containsEntry(MailJobHelper.RETRY_ATTEMPT_HEADER, 1);
        assertThat(channel.queueDeclarePassive(MailRetryTopology.queueName(queue, 30_000)).getMessageCount()).isEqualTo(1);
    }

    @Test void terminalDeadLetterIsRetainedWhileDlqBindingIsMissing() throws Exception {
        channel.queueUnbind(dlq, dlx, dlq);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        shared.convertAndSend(direct, "mail.send", request(), correlation);
        assertThat(correlation.getFuture().get(3, TimeUnit.SECONDS).isAck()).isTrue();
        await().atMost(Duration.ofSeconds(5)).until(() -> channel.queueDeclarePassive(queue).getMessageCount() == 1);
        GetResponse delivery = channel.basicGet(queue, false);
        channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                assertThat(channel.queueDeclarePassive(dlq).getMessageCount()).isZero());
        channel.queueBind(dlq, dlx, dlq);
        assertThat(receive(dlq, Duration.ofSeconds(210)).getProps().getHeaders()).containsKey("x-death");
        assertThat(channel.basicGet(dlq, true)).isNull();
        System.out.println("MAIL_BROKER terminal: dlqRetainedDuringRouteLoss=true replayAfterRecovery=true");
    }

    @Test void delayedPersistentMessageSurvivesOwnedBrokerRestartAndReportsIdleFootprint() throws Exception {
        publisher.publishWithDelay(request(), 10_000, 2, "attempt=2");
        assertThat(channel.queueDeclarePassive(MailRetryTopology.queueName(queue, 10_000)).getMessageCount()).isEqualTo(1);
        var queues = BROKER.execInContainer("rabbitmqctl", "list_queues", "name", "type", "arguments", "--formatter", "json");
        assertThat(queues.getExitCode()).isZero();
        assertThat(queues.getStdout()).contains("at-least-once").contains("reject-publish");
        var plugins = BROKER.execInContainer("rabbitmq-plugins", "list", "-e", "-m");
        assertThat(plugins.getExitCode()).isZero();
        assertThat(plugins.getStdout()).doesNotContain("rabbitmq_delayed_message_exchange");
        var retryInterval = BROKER.execInContainer("rabbitmqctl", "eval",
                "application:get_env(rabbit, dead_letter_worker_publisher_confirm_timeout).");
        assertThat(retryInterval.getExitCode()).isZero();
        assertThat(retryInterval.getStdout().strip()).isEqualTo("{ok,180000}");
        System.out.println("MAIL_BROKER dlxRecoveryIntervalMs=180000 unchangedDefault=true");
        var memory = BROKER.execInContainer("rabbitmq-diagnostics", "memory_breakdown", "--unit", "bytes", "--formatter", "json");
        assertThat(memory.getExitCode()).isZero();
        System.out.println("MAIL_BROKER memory_13_queues=" + memory.getStdout().strip());
        int beforeRestartPort = connectedPort;
        fixture.abort(); factory.destroy();
        BROKER.getDockerClient().restartContainerCmd(BROKER.getContainerId()).withTimeout(5).exec();
        try {
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofSeconds(1)).until(() -> {
                try { connect(); return true; }
                catch (Exception unavailable) { if (factory != null) factory.destroy(); return false; }
            });
        } catch (RuntimeException failure) {
            var state = BROKER.getDockerClient().inspectContainerCmd(BROKER.getContainerId()).exec().getState();
            System.out.println("MAIL_BROKER restartFailure: running=" + state.getRunning()
                    + " oomKilled=" + state.getOOMKilled() + " exitCode=" + state.getExitCodeLong());
            throw failure;
        }
        assertThat(receive(queue).getProps().getHeaders()).containsEntry(MailJobHelper.RETRY_ATTEMPT_HEADER, 2);
        System.out.println("MAIL_BROKER restart: ownedBrokerOnly=true pluginAbsent=true durableRetryRecovered=true");
        System.out.println("MAIL_BROKER restartPorts: before=" + beforeRestartPort + " after=" + connectedPort);
    }

    @Test void sharedMandatoryDirectPublishStillReturnsMissingRoute() throws Exception {
        publisher.publishWithDelay(request(), 100, 1, "attempt=1");
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        shared.convertAndSend(direct, "not.bound", request(), correlation);
        assertThat(correlation.getFuture().get(3, TimeUnit.SECONDS).isAck()).isTrue();
        assertThat(correlation.getReturned()).isNotNull();
        assertThat(correlation.getReturned().getReplyCode()).isEqualTo(312);
    }

    private GetResponse receive(String name) throws Exception {
        return receive(name, Duration.ofSeconds(60));
    }

    private GetResponse receive(String name, Duration timeout) throws Exception {
        await().atMost(timeout).until(() -> channel.queueDeclarePassive(name).getMessageCount() > 0);
        GetResponse response = channel.basicGet(name, true);
        assertThat(response).isNotNull();
        return response;
    }

    private MailSendRequest request() {
        return new MailSendRequest("fixture@example.invalid", "fixture", "fixture", "fixture", MailKind.GENERIC, Map.of());
    }
}
