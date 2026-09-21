package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The client is wired only to the disposable container's mapped port; application Redis is never used. */
@Testcontainers
@Timeout(30)
class EventPlanRateGuardRedisIT {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379).withReuse(false);
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private EventPlanRateGuard guard;

    @BeforeEach
    void setup() {
        assertThat(REDIS.isRunning()).isTrue();
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        guard = new EventPlanRateGuard(redis);
    }

    @AfterEach
    void close() { if (factory != null) factory.destroy(); }

    @Test
    void previewAndWriteHaveIndependentAccountScopedLimitsWithoutExtendingRejectedWindows() {
        UUID actor = UUID.randomUUID();
        String writes = "soundconnect:event-plan:write:" + actor;
        String previews = "soundconnect:event-plan:preview:" + actor;
        for (int index = 0; index < 30; index++) guard.check(actor);
        Long writesTtl = redis.getExpire(writes, TimeUnit.MILLISECONDS);
        var writeFailure = catchThrowableOfType(() -> guard.check(actor), RateLimitedException.class);
        assertThat(writeFailure.getRetryAfterSeconds()).isBetween(1L, 60L);
        assertThat(redis.getExpire(writes, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(writesTtl);
        assertThat(redis.opsForValue().get(writes)).isEqualTo("30");
        for (int index = 0; index < 60; index++) guard.checkPreview(actor);
        Long previewsTtl = redis.getExpire(previews, TimeUnit.MILLISECONDS);
        var previewFailure = catchThrowableOfType(() -> guard.checkPreview(actor), RateLimitedException.class);
        assertThat(previewFailure.getRetryAfterSeconds()).isBetween(1L, 60L);
        assertThat(redis.getExpire(previews, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(previewsTtl);
        assertThat(redis.opsForValue().get(previews)).isEqualTo("60");
        UUID other = UUID.randomUUID();
        guard.check(other);
        guard.checkPreview(other);
        assertThat(redis.opsForValue().get("soundconnect:event-plan:write:" + other)).isEqualTo("1");
        assertThat(redis.opsForValue().get("soundconnect:event-plan:preview:" + other)).isEqualTo("1");
    }

    @Test
    void simultaneousWritesAllowExactlyThirtyOfFortyFiveRequests() throws Exception {
        UUID actor = UUID.randomUUID();
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(12)) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 0; index < 45; index++) {
                results.add(executor.submit(() -> {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start latch timed out");
                    try { guard.check(actor); return true; }
                    catch (RateLimitedException rejected) { return false; }
                }));
            }
            release.countDown();
            int accepted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(30);
        }
        assertThat(redis.opsForValue().get("soundconnect:event-plan:write:" + actor)).isEqualTo("30");
    }
}
