package com.berkayb.soundconnect.auth.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AuthRateLimiterTelemetryTest {
    @Test @SuppressWarnings("rawtypes")
    void recordsActualPermittedLimitedUnavailableAndDisabledDecisionsWithoutIdentityDimensions() {
        var registry = new SimpleMeterRegistry();
        try {
            var redis = mock(StringRedisTemplate.class); var properties = new AuthRateLimitProperties();
            var limiter = new AuthRateLimiter(redis, properties); limiter.setTelemetry(new AuthRateLimiterTelemetry(registry));
            doReturn(List.of(1L, 60L)).doReturn(List.of(100L, 30L))
                    .doThrow(new RedisConnectionFailureException("private-message-55"))
                    .when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

            assertThat(limiter.check("private-bucket-51", "192.0.2.51", properties.getLogin()).status())
                    .isEqualTo(AuthRateLimiter.Status.PERMITTED);
            assertThat(limiter.checkAccount("private-bucket-52", "private-account-53@example.test", properties.getLogin()).status())
                    .isEqualTo(AuthRateLimiter.Status.LIMITED);
            assertThat(limiter.check("private-bucket-54", "192.0.2.54", properties.getLogin()).status())
                    .isEqualTo(AuthRateLimiter.Status.UNAVAILABLE);
            properties.setEnabled(false);
            assertThat(limiter.checkAccount("private-bucket-56", "private-account-57@example.test", properties.getLogin()).allowed()).isTrue();

            assertRecorded(registry, "ip", "permitted"); assertRecorded(registry, "account", "limited");
            assertRecorded(registry, "ip", "unavailable"); assertRecorded(registry, "account", "disabled");
            verify(redis, times(3)).execute(any(RedisScript.class), anyList(), any(Object[].class)); verifyNoMoreInteractions(redis);
            assertThat(registry.getMeters()).hasSize(16).allSatisfy(meter -> {
                assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactlyInAnyOrder("dimension", "decision");
                assertThat(meter.getId().getTag("dimension")).isIn("ip", "account");
                assertThat(meter.getId().getTag("decision")).isIn("permitted", "limited", "unavailable", "disabled");
                assertThat(meter.getId().toString()).doesNotContain("private-", "192.0.2", "example.test");
            });
        } finally { registry.close(); }
    }

    @Test @SuppressWarnings("rawtypes")
    void recordingFailureCannotTurnRedisFailureIntoAPermittedDecision() {
        var redis = mock(StringRedisTemplate.class); var properties = new AuthRateLimitProperties();
        var limiter = new AuthRateLimiter(redis, properties); var telemetry = mock(AuthRateLimiterTelemetry.class);
        limiter.setTelemetry(telemetry);
        doThrow(new RedisConnectionFailureException("unavailable")).when(redis)
                .execute(any(RedisScript.class), anyList(), any(Object[].class));
        doThrow(new IllegalStateException("meter failure")).when(telemetry).record(any(), any(), anyLong());

        assertThat(limiter.check("login", "192.0.2.1", properties.getLogin())).isEqualTo(AuthRateLimiter.Decision.unavailable());
        verify(telemetry).record(eq(AuthRateLimiterTelemetry.Dimension.IP), eq(AuthRateLimiterTelemetry.Outcome.UNAVAILABLE), anyLong());
    }

    @Test void telemetryBeanAndMeterAllocationAreOptIn() {
        var runner = new ApplicationContextRunner().withUserConfiguration(AuthRateLimiterTelemetry.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(AuthRateLimiterTelemetry.class);
            assertThat(context.getBean(MeterRegistry.class).getMeters()).isEmpty();
        });
        runner.withPropertyValues("app.telemetry.metrics.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(AuthRateLimiterTelemetry.class);
            assertThat(context.getBean(MeterRegistry.class).getMeters()).hasSize(16);
        });
    }

    private static void assertRecorded(MeterRegistry registry, String dimension, String decision) {
        assertThat(registry.get("soundconnect.auth.ratelimit.decisions").tags("dimension", dimension, "decision", decision).counter().count()).isEqualTo(1);
        var timer = registry.get("soundconnect.auth.ratelimit.duration").tags("dimension", dimension, "decision", decision).timer();
        assertThat(timer.count()).isEqualTo(1); assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isNotNegative();
    }
}
