package com.berkayb.soundconnect.modules.notification.dlq;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real loopback TCP refusal/stalled-handshake fixtures, not a Rabbit broker substitute. */
class NotificationDlqTransportTest {
    NotificationDlqProperties config() {
        var c = new NotificationDlqProperties(); c.setRpcTimeoutMs(200); c.setDeadlineMs(1000); return c;
    }
    NotificationDlqOperations operations(NotificationDlqBroker b, NotificationDlqProperties c) {
        return new NotificationDlqOperations(b, c, new SimpleMeterRegistry(), "dlq", "ingress", "exchange", "notification.event");
    }
    @Test void offlineSocketNeverReportsZeroOrHealthy() throws Exception {
        int port;
        try (var reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = reserved.getLocalPort(); }
        var source = new CachingConnectionFactory("127.0.0.1", port);
        try (var broker = new NotificationDlqBroker(source, config())) {
            var ops = operations(broker, config()); ops.observe();
            await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> assertThat(ops.summary().status()).isEqualTo("UNAVAILABLE"));
            assertThat(ops.summary().readyMessages()).isNull(); assertThat(ops.summary().totalMessages()).isNull();
        } finally { source.destroy(); }
    }
    @Test void repeatedStalledHandshakesCloseSocketsAndRemoveCancelledDeadlines() throws Exception {
        try (var server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress()); var worker = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(5000);
            var accepted = worker.submit(() -> {
                int closed = 0;
                for (int i=0;i<2;i++) try (var socket = server.accept()) {
                    socket.setSoTimeout(4000);
                    while (socket.getInputStream().read() != -1) { /* deliberately no AMQP response */ }
                    closed++;
                }
                return closed;
            });
            var source = new CachingConnectionFactory("127.0.0.1", server.getLocalPort());
            var config = config();
            try (var broker = new NotificationDlqBroker(source, config)) {
                var ops = operations(broker, config);
                for (int i=0;i<2;i++) {
                    var previous = ops.summary().measuredAt();
                    ops.observe();
                    for (int j=0;j<100;j++) ops.observe(); // same single-flight future, no backlog
                    await().atMost(Duration.ofSeconds(4)).untilAsserted(() -> {
                        assertThat(ops.summary().measuredAt()).isNotNull().isNotEqualTo(previous);
                        assertThat(ops.summary().status()).isEqualTo("UNAVAILABLE");
                    });
                }
                assertThat(accepted.get(5, TimeUnit.SECONDS)).isEqualTo(2);
                var field = NotificationDlqBroker.class.getDeclaredField("timer"); field.setAccessible(true);
                assertThat(((ScheduledThreadPoolExecutor)field.get(broker)).getQueue()).isEmpty();
            } finally { source.destroy(); }
        }
    }
}
