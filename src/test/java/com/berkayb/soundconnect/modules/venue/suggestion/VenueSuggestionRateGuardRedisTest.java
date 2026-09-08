package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.auth.ratelimit.TrustedProxyClientAddressResolver;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Executes the production Lua against a disposable Redis, never the developer's Redis instance. */
@Testcontainers
class VenueSuggestionRateGuardRedisTest {
    private static final String PREFIX = "soundconnect:{venue-suggestions}:";
    private static final String GLOBAL = PREFIX + "global-hour";
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2.5-alpine")
            .withExposedPorts(6379).withReuse(false);
    private static LettuceConnectionFactory connections;
    private static StringRedisTemplate redis;
    private VenueSuggestionProperties properties;
    private VenueSuggestionRateGuard guard;

    @BeforeAll
    static void connectOnlyToDisposableRedis() {
        assertThat(REDIS.isRunning()).isTrue();
        connections = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connections.afterPropertiesSet();
        connections.start();
        redis = new StringRedisTemplate(connections);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void closeClient() {
        if (connections != null) connections.destroy();
    }

    @BeforeEach
    void configureFreshGlobalWindow() {
        assertThat(connections.getHostName()).isEqualTo(REDIS.getHost());
        assertThat(connections.getPort()).isEqualTo(REDIS.getMappedPort(6379));
        // Each case has distinct IP keys. Only this test container's exact shared counter needs removal.
        redis.delete(GLOBAL);
        properties = new VenueSuggestionProperties();
        guard = new VenueSuggestionRateGuard(redis, new TrustedProxyClientAddressResolver(List.of()), properties);
    }

    @Test
    void concurrentRequestsCannotExceedHourlyLimitAndAllAcceptedCountersReceiveTtl() throws Exception {
        String client = "203.0.113.45";
        int attempts = 12;
        var ready = new CountDownLatch(attempts);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(attempts);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int index = 0; index < attempts; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test start timed out");
                    try {
                        guard.check(request(client));
                        return true;
                    } catch (RateLimitedException limited) {
                        assertThat(limited.getRetryAfterSeconds()).isBetween(1L, 3600L);
                        return false;
                    }
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(properties.getHourlyLimit());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(redis.opsForValue().get(hour(client))).isEqualTo("5");
        assertThat(redis.opsForValue().get(day(client))).isEqualTo("5");
        assertThat(redis.opsForValue().get(GLOBAL)).isEqualTo("5");
        assertThat(redis.getExpire(hour(client))).isBetween(3500L, 3600L);
        assertThat(redis.getExpire(day(client))).isBetween(86300L, 86400L);
        assertThat(redis.getExpire(GLOBAL)).isBetween(3500L, 3600L);
    }

    @Test
    void exhaustedGlobalWindowDoesNotCreateOrIncrementOtherClientCounters() {
        properties.setHourlyLimit(10);
        properties.setDailyLimit(30);
        properties.setGlobalHourlyLimit(3);
        for (int index = 1; index <= 3; index++) guard.check(request("198.51.100." + index));
        redis.expire(GLOBAL, Duration.ofSeconds(17));
        String blockedClient = "198.51.100.99";

        RateLimitedException limited = catchThrowableOfType(() -> guard.check(request(blockedClient)),
                RateLimitedException.class);

        assertThat(limited.getRetryAfterSeconds()).isBetween(1L, 17L);
        assertThat(redis.opsForValue().get(GLOBAL)).isEqualTo("3");
        assertThat(redis.hasKey(hour(blockedClient))).isFalse();
        assertThat(redis.hasKey(day(blockedClient))).isFalse();
        for (int index = 1; index <= 3; index++) {
            assertThat(redis.opsForValue().get(hour("198.51.100." + index))).isEqualTo("1");
            assertThat(redis.opsForValue().get(day("198.51.100." + index))).isEqualTo("1");
        }
    }

    @Test
    void longestExhaustedWindowDeterminesRetryAndRejectionDoesNotRefreshAnyWindow() {
        String client = "192.0.2.47";
        redis.opsForValue().set(hour(client), "5", Duration.ofSeconds(11));
        redis.opsForValue().set(day(client), "15", Duration.ofSeconds(29));
        redis.opsForValue().set(GLOBAL, "200", Duration.ofSeconds(7));

        RateLimitedException limited = catchThrowableOfType(() -> guard.check(request(client)),
                RateLimitedException.class);

        assertThat(limited.getRetryAfterSeconds()).isBetween(25L, 29L);
        assertThat(redis.opsForValue().get(hour(client))).isEqualTo("5");
        assertThat(redis.opsForValue().get(day(client))).isEqualTo("15");
        assertThat(redis.opsForValue().get(GLOBAL)).isEqualTo("200");
        assertThat(redis.getExpire(hour(client))).isBetween(1L, 11L);
        assertThat(redis.getExpire(day(client))).isBetween(1L, 29L);
        assertThat(redis.getExpire(GLOBAL)).isBetween(1L, 7L);
    }

    private static String hour(String client) { return PREFIX + "hour:" + VenueSuggestionNormalizer.hash(client); }
    private static String day(String client) { return PREFIX + "day:" + VenueSuggestionNormalizer.hash(client); }
    private static MockHttpServletRequest request(String client) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/venue-suggestions");
        request.setRemoteAddr(client);
        return request;
    }
}
