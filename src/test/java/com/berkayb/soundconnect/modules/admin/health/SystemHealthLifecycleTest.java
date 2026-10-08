package com.berkayb.soundconnect.modules.admin.health;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.berkayb.soundconnect.modules.admin.health.SystemHealthSnapshot.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class SystemHealthLifecycleTest {
    @Test void probesCannotResolveOtherSingletonsUntilApplicationReady() {
        CountDownLatch probeCalled = new CountDownLatch(1);
        AtomicBoolean calledDuringInitialization = new AtomicBoolean();
        AtomicBoolean dependencyInitialized = new AtomicBoolean();
        try (var context = new AnnotationConfigApplicationContext()) {
            var probe = new SystemHealthProbe("database", "Database", "Availability", () -> {
                probeCalled.countDown();
                // Real production probes can resolve ObjectProvider-backed singletons.
                // This lookup must happen only after singleton initialization finishes.
                context.getBean("lateDependency");
                assertThat(dependencyInitialized.get()).isTrue();
                return new SystemHealthProbe.Reading(Status.UP, ReasonCode.HEALTHY, Instant.now(), Map.of());
            });
            context.registerBean(SystemHealthService.class, () -> service(List.of(probe), true));
            context.registerBean("lateDependency", Object.class, () -> {
                context.getBean(SystemHealthService.class);
                try { calledDuringInitialization.set(probeCalled.await(500, TimeUnit.MILLISECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                dependencyInitialized.set(true);
                return new Object();
            });
            context.refresh();
            var service = context.getBean(SystemHealthService.class);
            assertThat(calledDuringInitialization.get()).as("no probe may run while another singleton is initializing").isFalse();
            assertThat(service.snapshot().status()).isEqualTo(Status.UNKNOWN);
            ready(context);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(service.snapshot().status()).isEqualTo(Status.UP));
        }
    }

    @Test void healthSchedulerStartsOnceAtReadinessAndCannotRestartAfterClose() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        var service = service(List.of(), true);
        ReflectionTestUtils.setField(service, "scheduler", scheduler);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SystemHealthService.class, () -> service);
            context.refresh();
            verifyNoInteractions(scheduler);
            ready(context); ready(context);
            verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(15L), eq(TimeUnit.SECONDS));
            service.close();
            ready(context);
            verify(scheduler, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        }
        verify(scheduler, times(1)).shutdownNow();
    }

    @Test void diagnosticsRetentionWaitsForReadinessAndStartsOnlyOnce() {
        ScheduledExecutorService retention = mock(ScheduledExecutorService.class);
        var store = store(new MockEnvironment());
        ReflectionTestUtils.setField(store, "retention", retention);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MobileDiagnosticsStore.class, () -> store);
            context.refresh();
            verifyNoInteractions(retention);
            ready(context); ready(context);
            verify(retention, times(1)).scheduleWithFixedDelay(any(Runnable.class), eq(60L), eq(60L), eq(TimeUnit.SECONDS));
            store.close();
            ready(context);
            verify(retention, times(1)).scheduleWithFixedDelay(any(Runnable.class), anyLong(), anyLong(), any());
        }
        verify(retention, times(1)).shutdownNow();
    }

    @Test void disabledTestBackgroundRemainsInactive() {
        ScheduledExecutorService healthScheduler = mock(ScheduledExecutorService.class);
        ScheduledExecutorService retention = mock(ScheduledExecutorService.class);
        var service = service(List.of(), false);
        var store = store(new MockEnvironment().withProperty("spring.profiles.active", "test"));
        ReflectionTestUtils.setField(service, "scheduler", healthScheduler);
        ReflectionTestUtils.setField(store, "retention", retention);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SystemHealthService.class, () -> service);
            context.registerBean(MobileDiagnosticsStore.class, () -> store);
            context.refresh(); ready(context);
            verifyNoInteractions(healthScheduler, retention);
            service.close(); store.close(); ready(context);
            verify(healthScheduler, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
            verify(retention, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        }
    }

    @Test void shutdownBeforeReadinessCannotStartEitherEnabledScheduler() {
        ScheduledExecutorService healthScheduler = mock(ScheduledExecutorService.class);
        ScheduledExecutorService retention = mock(ScheduledExecutorService.class);
        var service = service(List.of(), true);
        var store = store(new MockEnvironment());
        ReflectionTestUtils.setField(service, "scheduler", healthScheduler);
        ReflectionTestUtils.setField(store, "retention", retention);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SystemHealthService.class, () -> service);
            context.registerBean(MobileDiagnosticsStore.class, () -> store);
            context.refresh();
            service.close(); store.close();
            ready(context);
            verify(healthScheduler, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
            verify(retention, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        }
    }

    private static SystemHealthService service(List<SystemHealthProbe> probes, boolean enabled) {
        return new SystemHealthService(probes, new SystemHealthProperties(), Clock.systemUTC(), enabled);
    }
    private static MobileDiagnosticsStore store(MockEnvironment environment) {
        return new MobileDiagnosticsStore(mock(DataSource.class), mock(PlatformTransactionManager.class),
                new MobileDiagnosticsProperties(), new ObjectMapper(), environment);
    }
    private static void ready(AnnotationConfigApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
    }
}
