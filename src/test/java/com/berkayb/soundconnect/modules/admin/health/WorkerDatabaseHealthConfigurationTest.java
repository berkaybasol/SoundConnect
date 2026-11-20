package com.berkayb.soundconnect.modules.admin.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerDatabaseHealthConfigurationTest {
    private final DataSource dataSource = mock(DataSource.class);
    private final ApplicationContextRunner worker = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("media-worker", "test"))
            .withUserConfiguration(WorkerDatabaseHealthConfiguration.class)
            .withBean(DataSource.class, () -> dataSource);

    @Test void defaultOptOutDoesNotCreateServiceOrAccessPool() {
        worker.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(SystemHealthService.class);
            verifyNoInteractions(dataSource);
        });
    }

    @Test void enabledWorkerUsesOnlyOwnSessionsWithoutApiAdminDependencyGraph() {
        worker.withPropertyValues("app.system-health.postgres-sessions-enabled=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SystemHealthService.class)
                    .doesNotHaveBean(SystemHealthSources.class).doesNotHaveBean(SystemHealthController.class);
            var snapshot = context.getBean(SystemHealthService.class).snapshot();
            assertThat(snapshot.components()).extracting(Component::id).containsExactly(PostgresHealthMetrics.SESSIONS);
            assertThat(snapshot.components().getFirst().status()).isEqualTo(Status.UNKNOWN);
            assertThat(context.getBean(SystemHealthProperties.class).isPostgresStatementsEnabled()).isFalse();
            verifyNoInteractions(dataSource);
        });
    }

    @Test void workerStatementsFlagFailsClosedInsteadOfCallingApiOnlyWrapper() {
        worker.withPropertyValues("app.system-health.postgres-sessions-enabled=true",
                "app.system-health.postgres-statements-enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(dataSource);
        });
    }

    @Test void apiAndServletContextsDoNotImportWorkerService() {
        new ApplicationContextRunner().withUserConfiguration(WorkerDatabaseHealthConfiguration.class)
                .withPropertyValues("app.system-health.postgres-sessions-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(SystemHealthService.class));
        new WebApplicationContextRunner().withUserConfiguration(WorkerDatabaseHealthConfiguration.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("media-worker", "test"))
                .withPropertyValues("app.system-health.postgres-sessions-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(SystemHealthService.class));
    }

    @Test void repeatedSingleProbeRefreshesReuseOneWorkerRatherThanEventuallyStartingFourCoreThreads() {
        Set<Long> workerIds = new HashSet<>();
        var clock = Clock.systemUTC();
        var probe = new SystemHealthProbe(PostgresHealthMetrics.SESSIONS, "DB", "own role", () -> {
            synchronized (workerIds) { workerIds.add(Thread.currentThread().threadId()); }
            return new SystemHealthProbe.Reading(Status.UP, ReasonCode.HEALTHY, clock.instant(), Map.of());
        });
        try (var service = new SystemHealthService(List.of(probe), new SystemHealthProperties(), clock, false)) {
            for (int i = 0; i < 8; i++) service.refresh();
            assertThat(workerIds).hasSize(1);
            assertThat(workerIds).doesNotContain(Thread.currentThread().threadId());
        }
    }
}
