package com.berkayb.soundconnect.modules.admin.health;

import io.lettuce.core.api.async.RedisServerAsyncCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Owned disposable Redis; INFO only, no application data, shared Redis or staging access. */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class RedisHealthMetricsIT {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    private LettuceConnectionFactory connection;

    @BeforeEach void connect() {
        var configuration = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300))
                .shutdownTimeout(Duration.ofMillis(100)).build();
        connection = new LettuceConnectionFactory(configuration, client);
        connection.afterPropertiesSet();
    }

    @AfterEach void releaseConnections() {
        if (connection != null) connection.destroy();
    }

    @Test void actualSpringLettuceNativeConnectionExportsOnlyTheNumericInfoProjection() {
        // Production calls the existing Redis health contributor before optional INFO;
        // establish its shared connection here without issuing any write command.
        try (var borrowed = connection.getConnection()) {
            assertThat(borrowed.getNativeConnection()).isInstanceOf(RedisServerAsyncCommands.class);
        }
        var telemetry = new RedisHealthMetrics(connection);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            var metrics = telemetry.read(TimeUnit.SECONDS.toNanos(1));
            assertThat(metrics).containsOnlyKeys("redisUsedMemoryBytes", "redisMaxMemoryBytes", "redisEvictedKeys",
                    "redisConnectedClients", "redisBlockedClients", "redisRejectedConnections",
                    "redisTotalConnectionsReceived", "redisMemoryFragmentationRatio", "redisInfoLatencyMillis");
            assertThat(metrics.values()).allSatisfy(value -> assertThat(value).isFinite().isNotNegative());
            assertThat(metrics.get("redisUsedMemoryBytes")).isPositive();
            assertThat(metrics.get("redisConnectedClients")).isGreaterThanOrEqualTo(1);
            assertThat(metrics.get("redisTotalConnectionsReceived")).isGreaterThanOrEqualTo(1);
            assertThat(metrics.get("redisMaxMemoryBytes")).isZero();
            assertThat(metrics.get("redisEvictedKeys")).isZero();
            assertThat(metrics.get("redisBlockedClients")).isZero();
            assertThat(metrics.toString()).doesNotContain("redis_version", "tcp_port", "run_id", "server", "master_host");
        });
    }
}
