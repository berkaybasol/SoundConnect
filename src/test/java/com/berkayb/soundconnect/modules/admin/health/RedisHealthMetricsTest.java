package com.berkayb.soundconnect.modules.admin.health;

import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.async.RedisServerAsyncCommands;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RedisHealthMetricsTest {
    @Test void projectsOnlyTheEightFixedFiniteNonnegativeNumbers() {
        assertThat(RedisHealthMetrics.project("""
                # Memory
                used_memory:4096
                maxmemory:0
                evicted_keys:2
                connected_clients:3
                blocked_clients:1
                rejected_connections:4
                total_connections_received:20
                mem_fragmentation_ratio:1.25
                master_host:private-host-41
                redis_version:private-version-42
                private-account-43:900
                """)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "redisUsedMemoryBytes", 4096d, "redisMaxMemoryBytes", 0d,
                "redisEvictedKeys", 2d, "redisConnectedClients", 3d,
                "redisBlockedClients", 1d, "redisRejectedConnections", 4d,
                "redisTotalConnectionsReceived", 20d, "redisMemoryFragmentationRatio", 1.25d));
    }

    @Test void malformedNegativeNonfiniteDuplicateAndOversizedValuesAreMissingRatherThanZero() {
        assertThat(RedisHealthMetrics.project("used_memory:-1\nmaxmemory:NaN\nevicted_keys:1.5\n"
                + "connected_clients:1e8\nblocked_clients: 3\nrejected_connections:+1\n"
                + "total_connections_received:999999999999999999999\nmem_fragmentation_ratio:Infinity\n")).isEmpty();
        assertThat(RedisHealthMetrics.project("used_memory:1\nused_memory:2\nused_memory:3\n")).isEmpty();
        assertThat(RedisHealthMetrics.project("used_memory:1\n" + "x".repeat(65_536))).isEmpty();
        assertThat(RedisHealthMetrics.project(null)).isEmpty();
        assertThat(RedisHealthMetrics.project("used_memory:0\r\nmem_fragmentation_ratio:0.0\r\n"))
                .containsExactlyInAnyOrderEntriesOf(Map.of("redisUsedMemoryBytes", 0d, "redisMemoryFragmentationRatio", 0d));
    }

    @Test void reusesExistingAsyncConnectionForThreeReadOnlySectionsAndClosesOnlyTheBorrowedHandle() {
        var f = fixture();
        when(f.commands.info("memory")).thenReturn(completed("used_memory:512\r\nmaxmemory:0\r\nmem_fragmentation_ratio:1.5\r\n"));
        when(f.commands.info("stats")).thenReturn(completed("evicted_keys:5\nrejected_connections:0\ntotal_connections_received:7\n"));
        when(f.commands.info("clients")).thenReturn(completed("connected_clients:4\nblocked_clients:0\n"));

        var metrics = f.subject.read(TimeUnit.SECONDS.toNanos(1));

        assertThat(metrics).hasSize(9).containsEntry("redisUsedMemoryBytes", 512d)
                .containsEntry("redisEvictedKeys", 5d).containsEntry("redisConnectedClients", 4d);
        assertThat(metrics.get("redisInfoLatencyMillis")).isFinite().isNotNegative();
        verify(f.commands).info("memory"); verify(f.commands).info("stats"); verify(f.commands).info("clients");
        verifyNoMoreInteractions(f.commands);
        verify(f.connection).close();
        verify(f.connections).getConnection(); verifyNoMoreInteractions(f.connections);
    }

    @Test void oneSharedDeadlineCancelsPendingInfoWithoutWaitingForTheRedisCommandTimeout() {
        var f = fixture();
        var memory = new Result(); var stats = new Result(); var clients = new Result();
        when(f.commands.info("memory")).thenReturn(memory);
        when(f.commands.info("stats")).thenReturn(stats);
        when(f.commands.info("clients")).thenReturn(clients);
        long started = System.nanoTime();

        assertThat(f.subject.read(TimeUnit.MILLISECONDS.toNanos(30))).isEmpty();

        assertThat(System.nanoTime() - started).isLessThan(TimeUnit.SECONDS.toNanos(1));
        assertThat(memory.isCancelled()).isTrue(); assertThat(stats.isCancelled()).isTrue();
        assertThat(clients.isCancelled()).isTrue(); verify(f.connection).close();
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void infoAclOrTransportFailureReturnsNoMeasurementAndLogsNoRawContent(CapturedOutput output) {
        var f = fixture();
        var failed = new Result(); failed.completeExceptionally(new IllegalStateException("private-token-44@private-host-45"));
        when(f.commands.info("memory")).thenReturn(failed);
        var pending = new Result(); when(f.commands.info("stats")).thenReturn(pending);
        when(f.commands.info("clients")).thenReturn(completed("private-account-46:3\n"));

        assertThat(f.subject.read(TimeUnit.SECONDS.toNanos(1))).isEmpty();

        assertThat(pending.isCancelled()).isTrue(); verify(f.connection).close();
        assertThat(output.getAll()).doesNotContain("private-token-44", "private-host-45", "private-account-46");
    }

    @Test void unsupportedDriverAndExhaustedBudgetDoNotIssueInfo() {
        var connections = mock(RedisConnectionFactory.class);
        var subject = new RedisHealthMetrics(connections);
        assertThat(subject.read(0)).isEmpty(); verifyNoInteractions(connections);
        var connection = mock(RedisConnection.class); when(connections.getConnection()).thenReturn(connection);
        when(connection.getNativeConnection()).thenReturn(new Object());
        assertThat(subject.read(TimeUnit.SECONDS.toNanos(1))).isEmpty();
        verify(connection).getNativeConnection(); verify(connection).close(); verifyNoMoreInteractions(connection);
    }

    @Test void interruptedHealthProbeRestoresInterruptionAndCancelsPendingRequests() throws Exception {
        var f = fixture();
        @SuppressWarnings("unchecked") RedisFuture<String> interrupted = mock(RedisFuture.class);
        when(interrupted.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException());
        when(f.commands.info("memory")).thenReturn(interrupted);
        var stats = new Result(); var clients = new Result();
        when(f.commands.info("stats")).thenReturn(stats); when(f.commands.info("clients")).thenReturn(clients);
        try {
            assertThat(f.subject.read(TimeUnit.SECONDS.toNanos(1))).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(interrupted).cancel(true); assertThat(stats.isCancelled()).isTrue(); assertThat(clients.isCancelled()).isTrue();
        } finally { Thread.interrupted(); }
        verify(f.connection).close();
    }

    @Test void alreadyInterruptedProbeDoesNotAcquireAConnection() {
        var connections = mock(RedisConnectionFactory.class);
        try {
            Thread.currentThread().interrupt();
            assertThat(new RedisHealthMetrics(connections).read(TimeUnit.SECONDS.toNanos(1))).isEmpty();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(connections);
        } finally { Thread.interrupted(); }
    }

    @Test void telemetryIsOptInAndBeanCreationHasNoRedisIo() {
        var connections = mock(RedisConnectionFactory.class);
        var runner = new ApplicationContextRunner().withUserConfiguration(RedisHealthMetrics.class)
                .withBean(RedisConnectionFactory.class, () -> connections);
        runner.run(context -> assertThat(context).doesNotHaveBean(RedisHealthMetrics.class));
        runner.withPropertyValues("app.telemetry.metrics.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RedisHealthMetrics.class));
        verifyNoInteractions(connections);
    }

    private record Fixture(RedisConnectionFactory connections, RedisConnection connection,
                           RedisServerAsyncCommands<?, ?> commands, RedisHealthMetrics subject) { }
    private Fixture fixture() {
        var connections = mock(RedisConnectionFactory.class); var connection = mock(RedisConnection.class);
        var commands = mock(RedisServerAsyncCommands.class);
        when(connections.getConnection()).thenReturn(connection); when(connection.getNativeConnection()).thenReturn(commands);
        return new Fixture(connections, connection, commands, new RedisHealthMetrics(connections));
    }
    private static Result completed(String value) { var result = new Result(); result.complete(value); return result; }
    private static final class Result extends CompletableFuture<String> implements RedisFuture<String> {
        @Override public String getError() { return null; }
        @Override public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            try { get(timeout, unit); return true; } catch (ExecutionException | TimeoutException unavailable) { return false; }
        }
    }
}
