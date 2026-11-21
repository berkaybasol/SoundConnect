package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.modules.notification.dlq.NotificationDlqOperations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SystemHealthSourcesTest {
    @TempDir Path directory;

    @Test void postgresSourcesDefaultDisabledIndependentlyOfExistingDatabaseHealth() {
        var fixture = fixture(new SystemHealthProperties());
        assertThat(read(fixture.sources, PostgresHealthMetrics.SESSIONS).status()).isEqualTo(Status.DISABLED);
        assertThat(read(fixture.sources, PostgresHealthMetrics.STATEMENTS).status()).isEqualTo(Status.DISABLED);
        assertThat(read(fixture.sources, "database").reasonCode()).isEqualTo(ReasonCode.NOT_CONFIGURED);
    }

    @Test void whitelistDropsExceptionsAndAllPrivateDynamicDetails() {
        Instant now = Instant.now();
        var health = Health.down(new IllegalStateException("private-token-and-email"))
                .withDetail("host", "private-host").withDetail("accountId", UUID.randomUUID())
                .withDetail("pending", 2).withDetail("inFlight", Double.NaN)
                .withDetail("counts", Map.of("PENDING", 3, "private-account-key", 4))
                .withDetail("oldestUndeliveredAt", now.minusSeconds(10)).build();
        var result = SystemHealthSources.sanitize(health, now);
        assertThat(result.status()).isEqualTo(Status.DOWN);
        assertThat(result.metrics()).containsOnlyKeys("pending", "oldestPendingAgeSeconds");
        assertThat(result.metrics()).containsEntry("pending", 3d).containsEntry("oldestPendingAgeSeconds", 10d);
        assertThat(result.toString()).doesNotContain("private", "exception", "accountId", "host");
        assertThat(SystemHealthSources.sanitize(Health.up().withDetail("state", "DISABLED").build(), now).status()).isEqualTo(Status.DISABLED);
        assertThat(SystemHealthSources.sanitize(Health.unknown().build(), now).measuredAt()).isNull();
    }

    @Test void mediaCountsArePassiveAndOldBacklogDegradesWithoutExposingRows() {
        var fixture = fixture(new SystemHealthProperties());
        var jdbc = fixture.jdbc;
        jdbc.execute("create table tbl_media_asset (status varchar(32), created_at timestamp, physical_deletion_not_before timestamp, deletion_requested_at timestamp, transcode_cleanup_not_before timestamp)");
        jdbc.execute("insert into tbl_media_asset (status,created_at) values ('TRANSCODE_QUEUED', CURRENT_TIMESTAMP - INTERVAL '1' HOUR), ('FAILED', CURRENT_TIMESTAMP), ('READY', CURRENT_TIMESTAMP)");
        var processing = read(fixture.sources, "mediaProcessing");
        assertThat(processing.status()).isEqualTo(Status.DEGRADED);
        assertThat(processing.metrics()).containsEntry("pending", 1d).containsEntry("failed", 1d);
        assertThat(processing.metrics().get("oldestPendingAgeSeconds")).isGreaterThanOrEqualTo(3500d);
        var cleanup = read(fixture.sources, "mediaCleanup");
        assertThat(cleanup.status()).isEqualTo(Status.UP);
        assertThat(cleanup.metrics()).containsExactlyEntriesOf(Map.of("pending", 0d));
        assertThat(jdbc.queryForObject("select count(*) from tbl_media_asset", Integer.class)).isEqualTo(3);
        jdbc.execute("insert into tbl_media_asset (status,created_at,physical_deletion_not_before) values ('DELETION_PENDING', CURRENT_TIMESTAMP - INTERVAL '30' DAY, CURRENT_TIMESTAMP + INTERVAL '1' MINUTE)");
        var freshDeletion = read(fixture.sources, "mediaCleanup");
        assertThat(freshDeletion.status()).isEqualTo(Status.UP);
        assertThat(freshDeletion.metrics()).containsEntry("pending", 1d).containsEntry("oldestPendingAgeSeconds", 0d);
    }

    @Test void pendingTimestampBecomesOnlyNumericAgeAndMissingFutureOrWrongTypeRemainsUnmeasured() {
        var now=Instant.parse("2026-10-12T00:00:00Z");
        assertThat(SystemHealthSources.sanitize(Health.up().withDetail("pending",1L)
                .withDetail("oldestPendingAt",now.minusSeconds(3600)).build(),now).metrics())
                .containsExactlyInAnyOrderEntriesOf(Map.of("pending",1d,"oldestPendingAgeSeconds",3600d));
        assertThat(SystemHealthSources.sanitize(Health.up().withDetail("oldestPendingAt",now).build(),now).metrics())
                .containsEntry("oldestPendingAgeSeconds",0d);
        for (Object invalid:java.util.List.of(now.plusSeconds(1),"none",Double.NaN,Double.POSITIVE_INFINITY,-1d)) {
            assertThat(SystemHealthSources.sanitize(Health.up().withDetail("pending",1L)
                    .withDetail("oldestPendingAt",invalid).build(),now).metrics())
                    .containsExactlyEntriesOf(Map.of("pending",1d));
        }
        assertThat(SystemHealthSources.sanitize(Health.up().withDetail("pending",0L).build(),now).metrics())
                .containsExactlyEntriesOf(Map.of("pending",0d));
    }

    @Test void workerRequiresExistingValidFreshReadOnlyMarkerAndKeepsItsOwnMeasurementTime() throws Exception {
        var properties = new SystemHealthProperties();
        var fixture = fixture(properties);
        assertThat(read(fixture.sources, "mediaWorker").reasonCode()).isEqualTo(ReasonCode.NOT_CONFIGURED);
        Path marker = directory.resolve("worker.ready"); properties.setMediaWorkerHealthFile(marker.toString());
        assertThat(read(fixture.sources, "mediaWorker").status()).isEqualTo(Status.UNKNOWN);
        Instant measured = Instant.now().minusSeconds(5);
        Files.writeString(marker, measured.getEpochSecond() + "\n");
        var up = read(fixture.sources, "mediaWorker");
        assertThat(up.status()).isEqualTo(Status.UP);
        assertThat(up.measuredAt()).isEqualTo(Instant.ofEpochSecond(measured.getEpochSecond()));
        Files.writeString(marker, Instant.now().minusSeconds(120).getEpochSecond() + "\n");
        assertThat(read(fixture.sources, "mediaWorker").status()).isEqualTo(Status.STALE);
        Files.writeString(marker, Instant.now().plusSeconds(120).getEpochSecond() + "\n");
        assertThat(read(fixture.sources, "mediaWorker").reasonCode()).isEqualTo(ReasonCode.INVALID_MEASUREMENT);
        Files.writeString(marker, "secret-token");
        assertThat(read(fixture.sources, "mediaWorker").toString()).doesNotContain("secret-token");
    }

    @Test void missingExternalMeasurementsAreUnknownAndDlqDoesNotConsumeOrReplay() {
        var fixture = fixture(new SystemHealthProperties());
        assertThat(read(fixture.sources, "storage").status()).isEqualTo(Status.UNKNOWN);
        assertThat(read(fixture.sources, "mobileErrors").status()).isEqualTo(Status.UNKNOWN);
        assertThat(read(fixture.sources, "database").reasonCode()).isEqualTo(ReasonCode.NOT_CONFIGURED);
        when(fixture.operations.summary()).thenReturn(new NotificationDlqOperations.Summary("UP", 0, null, null,
                Instant.now(), false, null, false, 10));
        var summary = read(fixture.sources, "notificationDlq");
        assertThat(summary.metrics()).containsExactlyEntriesOf(Map.of("readyMessages", 0d));
        verify(fixture.operations).summary(); verifyNoMoreInteractions(fixture.operations);
    }

    @Test void redisInfoAddsOnlyOptionalMeasurementsWhilePreservingContributorHealth() {
        var fixture = fixture(new SystemHealthProperties());
        when(fixture.registry.getContributor("redis")).thenReturn((HealthIndicator) () -> Health.up().build());
        var telemetry = mock(RedisHealthMetrics.class); fixture.sources.setRedisMetrics(telemetry);
        when(telemetry.read(anyLong())).thenReturn(Map.of("redisUsedMemoryBytes", 1024d, "redisInfoLatencyMillis", 2d));

        var result = read(fixture.sources, "redis");

        assertThat(result.status()).isEqualTo(Status.UP);
        assertThat(result.reasonCode()).isEqualTo(ReasonCode.HEALTHY); assertThat(result.measuredAt()).isNotNull();
        assertThat(result.metrics()).containsExactlyInAnyOrderEntriesOf(Map.of("redisUsedMemoryBytes", 1024d, "redisInfoLatencyMillis", 2d));
        verify(telemetry).read(longThat(remaining -> remaining > 0 && remaining <= 1_975_000_000L));
    }

    @Test void redisInfoUnavailableOrAdapterFailureCannotReplaceExistingHealthWithUnknown() {
        var fixture = fixture(new SystemHealthProperties());
        when(fixture.registry.getContributor("redis")).thenReturn((HealthIndicator) () -> Health.up().build());
        var telemetry = mock(RedisHealthMetrics.class); fixture.sources.setRedisMetrics(telemetry);
        when(telemetry.read(anyLong())).thenReturn(Map.of()).thenThrow(new IllegalStateException("private-connection-88"));
        for (int i = 0; i < 2; i++) {
            var result = read(fixture.sources, "redis");
            assertThat(result.status()).isEqualTo(Status.UP); assertThat(result.metrics()).isEmpty();
            assertThat(result.toString()).doesNotContain("private-connection-88");
        }
    }

    @Test void redisUnknownOrDownDoesNotTriggerAnotherRedisObservation() {
        var fixture = fixture(new SystemHealthProperties());
        var telemetry = mock(RedisHealthMetrics.class); fixture.sources.setRedisMetrics(telemetry);
        assertThat(read(fixture.sources, "redis").status()).isEqualTo(Status.UNKNOWN);
        when(fixture.registry.getContributor("redis")).thenReturn((HealthIndicator) () -> Health.down().build());
        assertThat(read(fixture.sources, "redis").status()).isEqualTo(Status.DOWN);
        verifyNoInteractions(telemetry);
    }

    @Test void redisContributorStillWorksWithoutOptionalTelemetryBean() {
        var fixture = fixture(new SystemHealthProperties());
        when(fixture.registry.getContributor("redis")).thenReturn((HealthIndicator) () -> Health.up().build());
        var result = read(fixture.sources, "redis");
        assertThat(result.status()).isEqualTo(Status.UP); assertThat(result.metrics()).isEmpty();
    }

    private record Fixture(SystemHealthSources sources, JdbcTemplate jdbc, NotificationDlqOperations operations,
                           HealthContributorRegistry registry) { }
    @SuppressWarnings("unchecked")
    private Fixture fixture(SystemHealthProperties properties) {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:health-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;INIT=SET TIME ZONE 'UTC'", "sa", "");
        ObjectProvider<NotificationDlqOperations> provider = mock(ObjectProvider.class);
        var operations = mock(NotificationDlqOperations.class); when(provider.getIfAvailable()).thenReturn(operations);
        var queues = mock(RabbitQueueHealthSources.class); when(queues.probes()).thenReturn(java.util.List.of());
        var registry = mock(HealthContributorRegistry.class);
        var sources = new SystemHealthSources(registry, provider, dataSource,
                new DataSourceTransactionManager(dataSource), new SimpleMeterRegistry(), properties, queues, mock(ObjectProvider.class));
        return new Fixture(sources, new JdbcTemplate(dataSource), operations, registry);
    }
    private static SystemHealthProbe.Reading read(SystemHealthSources sources, String id) {
        return sources.probes().stream().filter(probe -> probe.id().equals(id)).findFirst().orElseThrow().read().get();
    }
}
