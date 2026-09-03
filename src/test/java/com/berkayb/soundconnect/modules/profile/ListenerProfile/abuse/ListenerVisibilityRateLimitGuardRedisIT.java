package com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@DataRedisTest
@Import({
		ListenerVisibilityRateLimitConfiguration.class,
		ListenerVisibilityRateLimitGuard.class
})
@Timeout(30)
class ListenerVisibilityRateLimitGuardRedisIT {

	@Container
	static final GenericContainer<?> REDIS =
			new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("spring.data.redis.ssl.enabled", () -> false);
		registry.add("app.listener-profile.visibility-rate-limit.burst-capacity", () -> 3);
		registry.add("app.listener-profile.visibility-rate-limit.refill-period", () -> "10s");
	}

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ListenerVisibilityRateLimitGuard guard;

	@BeforeEach
	void clearRedis() {
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
	}

	@Test
	void realRedisScriptAllowsTheBurstThenReturnsRetryAfter() {
		UUID userId = UUID.randomUUID();

		assertThatCode(() -> {
			guard.check(userId);
			guard.check(userId);
			guard.check(userId);
		}).doesNotThrowAnyException();

		assertThatThrownBy(() -> guard.check(userId))
				.isInstanceOfSatisfying(RateLimitedException.class, exception ->
						assertThat(exception.getRetryAfterSeconds()).isBetween(1L, 10L));
	}

	@Test
	void realRedisScriptAtomicallyCapsAConcurrentBurst() throws Exception {
		UUID userId = UUID.randomUUID();
		int requestCount = 12;
		CountDownLatch ready = new CountDownLatch(requestCount);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(requestCount);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int i = 0; i < requestCount; i++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					try {
						guard.check(userId);
						return true;
					} catch (RateLimitedException expected) {
						return false;
					}
				}));
			}

			ready.await();
			start.countDown();
			long permitted = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					permitted++;
				}
			}

			assertThat(permitted).isEqualTo(3L);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void policyCapacityReductionImmediatelyClampsAnOlderTokenBalance() {
		UUID userId = UUID.randomUUID();
		String key = "soundconnect:listener-profile:visibility-rate-limit:user:" + userId;
		redisTemplate.opsForHash().putAll(key, Map.of(
				"tokens", "99",
				"last_refill_ms", Long.toString(System.currentTimeMillis())
		));

		assertThatCode(() -> {
			guard.check(userId);
			guard.check(userId);
			guard.check(userId);
		}).doesNotThrowAnyException();
		assertThatThrownBy(() -> guard.check(userId)).isInstanceOf(RateLimitedException.class);
	}
}
