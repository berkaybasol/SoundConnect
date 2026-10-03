package com.berkayb.soundconnect.shared.mail.consumer;

import com.rabbitmq.client.AMQP;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Required real broker check: no application broker, SMTP, or message fixture is used. */
@Testcontainers
class MailDlqRetentionRabbitIT {
    @Container
    static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @Test
    void moreThanPrefetchRemainsReadyAcrossMonitoringAndConnectionRestart() throws Exception {
        String queue = "mail.retention.test." + UUID.randomUUID();
        // Production mail listener's default prefetch is 10. These 25 failed
        // jobs must remain ready, even after many polls and a new app connection.
        int count = 25;
        byte[] body = "private-test-mail-and-otp-do-not-log".getBytes(StandardCharsets.UTF_8);
        CachingConnectionFactory first = connection();
        try {
            RabbitAdmin admin = new RabbitAdmin(first);
            admin.declareQueue(new Queue(queue, true, false, false));
            var channel = first.createConnection().createChannel(false);
            try {
                channel.confirmSelect();
                for (int i = 0; i < count; i++) {
                    channel.basicPublish("", queue,
                            new AMQP.BasicProperties.Builder().deliveryMode(2).messageId("job-" + i).build(), body);
                }
                channel.waitForConfirmsOrDie(5_000);
            } finally {
                channel.close();
            }
            var monitor = new DlqMailJobConsumer(admin, queue);
            for (int i = 0; i < 30; i++) monitor.observeDepth();
            assertReadyOnly(queue, count);
        } finally {
            first.destroy();
        }

        CachingConnectionFactory reopened = connection();
        try {
            var monitor = new DlqMailJobConsumer(new RabbitAdmin(reopened), queue);
            for (int i = 0; i < 30; i++) monitor.observeDepth();
            assertReadyOnly(queue, count);
            // Only the test harness now consumes its own fixtures, proving the
            // retained body, identity, order and persistent delivery property.
            var channel = reopened.createConnection().createChannel(false);
            try {
                for (int i = 0; i < count; i++) {
                    var retained = channel.basicGet(queue, true);
                    assertThat(retained).isNotNull();
                    assertThat(retained.getBody()).isEqualTo(body);
                    assertThat(retained.getProps().getMessageId()).isEqualTo("job-" + i);
                    assertThat(retained.getProps().getDeliveryMode()).isEqualTo(2);
                }
                assertThat(channel.basicGet(queue, true)).isNull();
            } finally {
                channel.close();
            }
        } finally {
            reopened.destroy();
        }
    }

    private static CachingConnectionFactory connection() {
        var connection = new CachingConnectionFactory(BROKER.getHost(), BROKER.getAmqpPort());
        connection.setUsername(BROKER.getAdminUsername());
        connection.setPassword(BROKER.getAdminPassword());
        return connection;
    }

    private static void assertReadyOnly(String queue, int count) throws Exception {
        var result = BROKER.execInContainer("rabbitmqctl", "list_queues", "--quiet",
                "name", "messages_ready", "messages_unacknowledged", "consumers");
        assertThat(result.getExitCode()).isZero();
        assertThat(result.getStdout().lines().filter(line -> line.startsWith(queue + "\t")).toList())
                .containsExactly(queue + "\t" + count + "\t0\t0");
    }
}
