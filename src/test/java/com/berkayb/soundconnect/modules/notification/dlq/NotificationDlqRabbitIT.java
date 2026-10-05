package com.berkayb.soundconnect.modules.notification.dlq;

import com.rabbitmq.client.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@Testcontainers
class NotificationDlqRabbitIT {
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("soundconnect-rabbitmq:3.13.7").asCompatibleSubstituteFor("rabbitmq"))
            .withLabel("soundconnect.fixture", "bil005-dlq-rabbit").withReuse(false);
    CachingConnectionFactory source;
    NotificationDlqProperties config;
    NotificationDlqBroker broker;
    NotificationDlqOperations ops;
    com.rabbitmq.client.Connection fixture;
    Channel channel;
    String dlq, ingress, exchange;
    @BeforeEach void setup() throws Exception {
        assertThat(RABBIT.getContainerInfo().getConfig().getLabels()).containsEntry("soundconnect.fixture", "bil005-dlq-rabbit");
        source = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        source.setUsername(RABBIT.getAdminUsername()); source.setPassword(RABBIT.getAdminPassword());
        config = new NotificationDlqProperties(); config.setReplayEnabled(true); config.setRpcTimeoutMs(2000); config.setDeadlineMs(10000); config.setWindow(3);
        broker = new NotificationDlqBroker(source, config);
        String id = UUID.randomUUID().toString(); dlq = "dlq." + id; ingress = "ingress." + id; exchange = "exchange." + id;
        fixture = source.getRabbitConnectionFactory().newConnection(); channel = fixture.createChannel();
        channel.exchangeDeclare(exchange, "direct", true);
        channel.queueDeclare(dlq, true, false, false, Map.of()); channel.queueDeclare(ingress, true, false, false, Map.of());
        channel.queueBind(ingress, exchange, "notification.event");
        ops = operations(broker);
    }
    NotificationDlqOperations operations(NotificationDlqBroker b) { return new NotificationDlqOperations(b, config, new SimpleMeterRegistry(), dlq, ingress, exchange, "notification.event"); }
    @AfterEach void cleanup() throws Exception { broker.close(); if (fixture != null) fixture.abort(); if (source != null) source.destroy(); }
    void send(String text) throws Exception { channel.basicPublish("", dlq, new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build(), text.getBytes()); channel.queueDeclarePassive(dlq); }
    int ready(String q) throws Exception { return channel.queueDeclarePassive(q).getMessageCount(); }
    void depth(int expected) { await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> assertThat(ready(dlq)).isEqualTo(expected)); }
    NotificationDlqOperations.Selection select() {
        var m = ops.inspect(UUID.randomUUID()).messages().stream().filter(NotificationDlqMessage.Metadata::replayable).findFirst().orElseThrow();
        return new NotificationDlqOperations.Selection(m.eventId(), m.fingerprint());
    }
    void observe(NotificationDlqOperations o, int expected) {
        o.observe(); await().atMost(Duration.ofSeconds(6)).untilAsserted(() -> assertThat(o.summary().readyMessages()).isEqualTo(expected));
    }
    @Test void passiveObservationKeepsBodyIdentityOrderAndManualDeliveryState() throws Exception {
        observe(ops, 0);
        for (int i = 0; i < 5; i++) send("body-" + i);
        var held = channel.basicGet(dlq, false);
        for (int i = 0; i < 4; i++) observe(ops, 4); // Each observation opens/closes a dedicated connection.
        assertThat(ops.summary().unackedMessages()).isNull(); assertThat(ops.summary().totalMessages()).isNull();
        assertThat(ops.summary().oldest().seconds()).isNull();
        // Same original channel/tag remains unacked; queue counts never treated as total.
        channel.basicAck(held.getEnvelope().getDeliveryTag(), false);
        for (int i = 1; i < 5; i++) {
            var d = channel.basicGet(dlq, true);
            assertThat(new String(d.getBody())).isEqualTo("body-" + i);
            assertThat(d.getEnvelope().isRedeliver()).isFalse();
        }
        assertThat(ready(dlq)).isZero();
    }
    @Test void inspectionHoldsFiniteWindowAndPreservesAllNonselectedPoison() throws Exception {
        send("bad-json"); send("{}"); send(NotificationDlqMessageTest.BODY); send("outside-window");
        var result = ops.inspect(UUID.randomUUID());
        assertThat(result.examined()).isEqualTo(3); depth(4);
        assertThat(result.messages()).hasSize(3); assertThat(result.messages().get(0).replayable()).isFalse();
        var selected = result.messages().get(2);
        var replayed = ops.replay(UUID.randomUUID(), new NotificationDlqOperations.Selection(selected.eventId(), selected.fingerprint()));
        assertThat(replayed.outcome()).isEqualTo("REPLAYED"); depth(3);
        assertThat(new String(channel.basicGet(ingress, true).getBody())).isEqualTo(NotificationDlqMessageTest.BODY);
        List<String> remaining = new ArrayList<>(); for (int i=0;i<3;i++) remaining.add(new String(channel.basicGet(dlq, true).getBody()));
        assertThat(remaining).containsExactlyInAnyOrder("bad-json", "{}", "outside-window");
    }
    @Test void realMandatoryReturnPreservesSource() throws Exception {
        send(NotificationDlqMessageTest.BODY); var selected = select(); depth(1);
        channel.queueUnbind(ingress, exchange, "notification.event");
        var result = ops.replay(UUID.randomUUID(), selected);
        assertThat(result.outcome()).isEqualTo("UNROUTABLE"); assertThat(result.sourceAck()).isEqualTo("NOT_SENT"); depth(1);
        assertThat(ready(ingress)).isZero();
    }
    @Test void matchingRecordOutsideFiniteWindowIsNotClaimedAbsentOrAcked() throws Exception {
        send("first"); send("second"); send("third"); send(NotificationDlqMessageTest.BODY);
        var m = NotificationDlqMessage.parse(NotificationDlqMessageTest.delivery(NotificationDlqMessageTest.BODY, Map.of()),
                "/\n" + dlq, ingress, config.getMaxBodyBytes(), java.time.Instant.now()).metadata();
        assertThat(ops.replay(UUID.randomUUID(), new NotificationDlqOperations.Selection(m.eventId(), m.fingerprint())).outcome()).isEqualTo("NOT_FOUND_IN_WINDOW");
        depth(4); assertThat(ready(ingress)).isZero();
    }
    @Test void realConnectionAbortAfterConfirmBeforeAckRequeuesSourceWithDuplicatePossible() throws Exception {
        send(NotificationDlqMessageTest.BODY); var selected = select(); depth(1);
        try (var interrupted = new NotificationDlqBroker(source, config) {
            @Override <T> Future<T> submit(boolean passive, Work<T> work) {
                return super.submit(passive, s -> {
                    Channel original = s.channel;
                    s.channel = (Channel) java.lang.reflect.Proxy.newProxyInstance(Channel.class.getClassLoader(), new Class[]{Channel.class}, (proxy, method, args) -> {
                        if (method.getName().equals("basicAck")) { s.cancel(); throw new IOException("controlled before-source-ACK cut"); }
                        try { return method.invoke(original, args); }
                        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                    });
                    return work.run(s);
                });
            }
        }) {
            var result = operations(interrupted).replay(UUID.randomUUID(), selected);
            assertThat(result.outcome()).isEqualTo("SOURCE_ACK_AMBIGUOUS"); assertThat(result.brokerAcceptance()).isEqualTo("CONFIRMED_ROUTED");
        }
        depth(1); assertThat(ready(ingress)).isEqualTo(1);
        assertThat(ops.replay(UUID.randomUUID(), selected).outcome()).isEqualTo("REPLAYED");
        depth(0); assertThat(ready(ingress)).isEqualTo(2);
    }
    @Test void twoInstancesRaceWithoutAckingDifferentMessage() throws Exception {
        send(NotificationDlqMessageTest.BODY); send("sibling"); var selected = select(); depth(2);
        try (var second = new NotificationDlqBroker(source, config); var executor = Executors.newFixedThreadPool(2)) {
            var other = operations(second); var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return ops.replay(UUID.randomUUID(), selected); });
            var b = executor.submit(() -> { start.await(); return other.replay(UUID.randomUUID(), selected); }); start.countDown();
            assertThat(List.of(a.get(8, TimeUnit.SECONDS).outcome(), b.get(8, TimeUnit.SECONDS).outcome())).containsExactlyInAnyOrder("REPLAYED", "NOT_FOUND_IN_WINDOW");
        }
        depth(1); assertThat(new String(channel.basicGet(dlq, true).getBody())).isEqualTo("sibling"); assertThat(ready(ingress)).isEqualTo(1);
    }
    @Test void realBrokerAlarmCausesConfirmTimeoutAndSourceSurvives() throws Exception {
        send(NotificationDlqMessageTest.BODY); var selected = select(); depth(1);
        assertThat(RABBIT.execInContainer("rabbitmqctl", "set_vm_memory_high_watermark", "absolute", "1").getExitCode()).isZero();
        try {
            var result = ops.replay(UUID.randomUUID(), selected);
            assertThat(result.outcome()).isIn("PUBLISH_AMBIGUOUS", "UNAVAILABLE_OR_AMBIGUOUS");
            assertThat(result.sourceAck()).isIn("NOT_SENT", "UNKNOWN");
        } finally { assertThat(RABBIT.execInContainer("rabbitmqctl", "set_vm_memory_high_watermark", "0.4").getExitCode()).isZero(); }
        depth(1);
    }
    @Test void missingQueueAndStaleObservationAreUnknown() throws Exception {
        observe(ops, 0);
        config.setStaleMs(10);
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(ops.summary().status()).isEqualTo("UNKNOWN"));
        config.setStaleMs(90000); channel.queueDelete(dlq); ops.observe();
        await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(ops.summary().status()).isEqualTo("UNAVAILABLE"));
        assertThat(ops.summary().readyMessages()).isNull();
    }
    @Test void oversizedTransportRemainsRetainedAndWorkerRecovers() throws Exception {
        send("x".repeat(config.getMaxBodyBytes() + 2));
        assertThat(ops.inspect(UUID.randomUUID()).outcome()).isEqualTo("UNAVAILABLE_OR_AMBIGUOUS"); depth(1);
        observe(ops, 1);
    }
    @Test void deadlineReleasesHeldDeliveryAndRejectsUnboundedBacklogAndShutdown() throws Exception {
        config.setDeadlineMs(1200);
        send(NotificationDlqMessageTest.BODY);
        var held = new CountDownLatch(1);
        Future<?> task = broker.submit(false, s -> { s.channel.basicGet(dlq, false); held.countDown(); Thread.sleep(10000); return null; });
        assertThat(held.await(3, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 100; i++) assertThatThrownBy(() -> broker.submit(false, s -> null)).isInstanceOf(RejectedExecutionException.class);
        depth(1); // Deadline aborts the actual channel independently of the deliberately stuck worker.
        broker.close();
        assertThatThrownBy(() -> broker.submit(false, s -> null)).isInstanceOf(RejectedExecutionException.class);
        await().atMost(Duration.ofSeconds(3)).until(task::isDone); depth(1);
    }
    @Test void recordsActualBrokerVersionAndClassicQueuePolicyBoundary() throws Exception {
        String version = RABBIT.execInContainer("rabbitmqctl", "version").getStdout().trim();
        assertThat(version).isEqualTo("3.13.7");
        String policies = RABBIT.execInContainer("rabbitmqctl", "list_policies", "--formatter", "json").getStdout();
        String types = RABBIT.execInContainer("rabbitmqctl", "list_queues", "name", "type", "--formatter", "json").getStdout();
        assertThat(types).contains("classic");
        System.out.println("BIL005_RABBIT version=" + version + " image=" + RABBIT.getDockerImageName() + " fixture=" + RABBIT.getContainerId() + " policies=" + policies.trim() + " types=" + types.trim());
    }
}
