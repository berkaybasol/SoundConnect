package com.berkayb.soundconnect.modules.feed.musician.abuse;

import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers(disabledWithoutDocker = true)
@DataRedisTest
@Import({MusicianFeedRateLimitConfiguration.class, MusicianFeedRateLimitGuard.class})
@Timeout(30)
class MusicianFeedRateLimitGuardRedisIT {
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.ssl.enabled", () -> false);
        registry.add("app.feed.musician.rate-limit.enabled", () -> true);
        registry.add("app.feed.musician.rate-limit.initial.burst-capacity", () -> 2);
        registry.add("app.feed.musician.rate-limit.initial.refill-period", () -> "30s");
        registry.add("app.feed.musician.rate-limit.continuation.burst-capacity", () -> 4);
        registry.add("app.feed.musician.rate-limit.continuation.refill-period", () -> "30s");
        registry.add("app.feed.musician.rate-limit.shared-page-budget.burst-capacity", () -> 4);
        registry.add("app.feed.musician.rate-limit.shared-page-budget.refill-period", () -> "30s");
        registry.add("app.feed.musician.rate-limit.telemetry.burst-capacity", () -> 2);
        registry.add("app.feed.musician.rate-limit.telemetry.refill-period", () -> "30s");
        registry.add("app.feed.musician.rate-limit.feedback.burst-capacity", () -> 2);
        registry.add("app.feed.musician.rate-limit.feedback.refill-period", () -> "30s");
    }

    @Autowired StringRedisTemplate redisTemplate;
    @Autowired MusicianFeedRateLimitGuard guard;

    @BeforeEach
    void clearRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void realRedisKeepsInitialContinuationAndTelemetryBucketsIndependent() {
        UUID initialUser = UUID.randomUUID();
        assertThatCode(() -> {
            guard.checkPage(initialUser, false);
            guard.checkPage(initialUser, false);
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.checkPage(initialUser, false))
                .isInstanceOf(RateLimitedException.class);

        UUID continuationUser = UUID.randomUUID();
        assertThatCode(() -> {
            for (int request = 0; request < 4; request++) guard.checkPage(continuationUser, true);
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.checkPage(continuationUser, true))
                .isInstanceOf(RateLimitedException.class);

        UUID telemetryUser = UUID.randomUUID();
        guard.checkTelemetry(telemetryUser);
        guard.checkTelemetry(telemetryUser);
        assertThatThrownBy(() -> guard.checkTelemetry(telemetryUser))
                .isInstanceOf(RateLimitedException.class);
        assertThatCode(() -> guard.checkPage(telemetryUser, false)).doesNotThrowAnyException();
    }

    @Test
    void feedbackIsIndependentOfPageTelemetryAndOtherAccounts() {
        UUID feedbackFirst = UUID.randomUUID();
        guard.checkFeedback(feedbackFirst);
        guard.checkFeedback(feedbackFirst);
        assertThatThrownBy(() -> guard.checkFeedback(feedbackFirst))
                .isInstanceOf(RateLimitedException.class);
        assertThatCode(() -> {
            guard.checkPage(feedbackFirst, false);
            guard.checkTelemetry(feedbackFirst);
        }).doesNotThrowAnyException();

        UUID pagesFirst = UUID.randomUUID();
        for (int request = 0; request < 4; request++) guard.checkPage(pagesFirst, true);
        guard.checkTelemetry(pagesFirst);
        guard.checkTelemetry(pagesFirst);
        assertThatThrownBy(() -> guard.checkPage(pagesFirst, true))
                .isInstanceOf(RateLimitedException.class);
        assertThatThrownBy(() -> guard.checkTelemetry(pagesFirst))
                .isInstanceOf(RateLimitedException.class);
        assertThatCode(() -> {
            guard.checkFeedback(pagesFirst);
            guard.checkFeedback(pagesFirst);
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.checkFeedback(pagesFirst))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void exhaustedFeedbackBudgetReplenishesUsingRedisTimeWithoutDeletingItsKey() {
        MusicianFeedRateLimitGuard fastGuard = feedbackGuard(2, Duration.ofSeconds(1));
        UUID userId = UUID.randomUUID();
        String key = feedbackKey(userId);
        fastGuard.checkFeedback(userId);
        fastGuard.checkFeedback(userId);
        long firstRefillAt = Long.parseLong((String) redisTemplate.opsForHash().get(key, "last_refill_ms"));
        assertThatThrownBy(() -> fastGuard.checkFeedback(userId))
                .isInstanceOf(RateLimitedException.class);

        // Rejected polls preserve and refresh the same live key. Admission must
        // therefore come from replenishment, not from expiry resetting capacity.
        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThatCode(() -> fastGuard.checkFeedback(userId))
                        .doesNotThrowAnyException());

        long nextRefillAt = Long.parseLong((String) redisTemplate.opsForHash().get(key, "last_refill_ms"));
        assertThat(nextRefillAt - firstRefillAt).isGreaterThanOrEqualTo(1_000L);
        assertThat((nextRefillAt - firstRefillAt) % 1_000L).isZero();
        assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 4_000L);
    }

    @Test
    void idleFeedbackBudgetExpiresAndNextUseReceivesTheFullBurst() {
        MusicianFeedRateLimitGuard fastGuard = feedbackGuard(2, Duration.ofMillis(500));
        UUID userId = UUID.randomUUID();
        String key = feedbackKey(userId);
        fastGuard.checkFeedback(userId);
        fastGuard.checkFeedback(userId);
        assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 2_000L);

        await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(redisTemplate.hasKey(key)).isFalse());

        assertThatCode(() -> {
            fastGuard.checkFeedback(userId);
            fastGuard.checkFeedback(userId);
        }).doesNotThrowAnyException();
        assertThat(redisTemplate.hasKey(key)).isTrue();
    }

    @Test
    void sharedPageBudgetCannotBeBypassedByStartingAnotherLane() {
        UUID userId = UUID.randomUUID();
        guard.checkPage(userId, false);
        guard.checkPage(userId, false);
        guard.checkPage(userId, true);
        guard.checkPage(userId, true);

        // Two continuation-lane tokens remain, but the shared page budget is empty.
        assertThatThrownBy(() -> guard.checkPage(userId, true))
                .isInstanceOfSatisfying(RateLimitedException.class, exception ->
                        assertThat(exception.getRetryAfterSeconds()).isBetween(1L, 30L));
        String continuationKey = "soundconnect:musician-feed:rate-limit:{"
                + userId + "}:continuation";
        assertThat(redisTemplate.opsForHash().get(continuationKey, "tokens"))
                .as("a shared-budget rejection must not partially debit the lane bucket")
                .isEqualTo("2");
    }

    @Test
    void mixedConcurrentTrafficAtomicallyHonorsTheSharedBudget() throws Exception {
        UUID userId = UUID.randomUUID();
        int requestCount = 10;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                boolean continuation = index >= 2;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        guard.checkPage(userId, continuation);
                        return true;
                    } catch (RateLimitedException expected) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();

            long accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) accepted++;
            }
            assertThat(accepted).isEqualTo(4L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void redisKeysHaveBoundedTtlAndShareTheAccountHashTag() {
        UUID userId = UUID.randomUUID();
        guard.checkPage(userId, false);

        String initialKey = "soundconnect:musician-feed:rate-limit:{" + userId + "}:initial";
        String sharedKey = "soundconnect:musician-feed:rate-limit:{" + userId + "}:pages";
        assertThat(redisTemplate.hasKey(initialKey)).isTrue();
        assertThat(redisTemplate.hasKey(sharedKey)).isTrue();
        assertThat(redisTemplate.getExpire(initialKey, TimeUnit.MILLISECONDS)).isBetween(1L, 120_000L);
        assertThat(redisTemplate.getExpire(sharedKey, TimeUnit.MILLISECONDS)).isBetween(1L, 240_000L);
    }

    private MusicianFeedRateLimitGuard feedbackGuard(int capacity, Duration refillPeriod) {
        MusicianFeedRateLimitProperties properties = new MusicianFeedRateLimitProperties();
        properties.setEnabled(true);
        properties.getFeedback().setBurstCapacity(capacity);
        properties.getFeedback().setRefillPeriod(refillPeriod);
        return new MusicianFeedRateLimitGuard(redisTemplate, properties);
    }

    private String feedbackKey(UUID userId) {
        return "soundconnect:musician-feed:rate-limit:{" + userId + "}:feedback";
    }
}
