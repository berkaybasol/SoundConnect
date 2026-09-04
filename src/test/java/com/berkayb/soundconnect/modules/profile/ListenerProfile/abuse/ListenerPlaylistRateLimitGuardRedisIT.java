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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@DataRedisTest
@Import({
		ListenerPlaylistRateLimitConfiguration.class,
		ListenerPlaylistRateLimitGuard.class
})
@Timeout(30)
class ListenerPlaylistRateLimitGuardRedisIT {

	@Container
	static final GenericContainer<?> REDIS =
			new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("spring.data.redis.ssl.enabled", () -> false);
		registry.add("app.listener-profile.playlist-rate-limit.limit", () -> 10);
		registry.add("app.listener-profile.playlist-rate-limit.window", () -> "1m");
	}

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private ListenerPlaylistRateLimitGuard guard;

	@BeforeEach
	void clearRedis() {
		redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
	}

	@Test
	void realRedisScriptAllowsTenRequestsThenReturnsWindowRetryAfter() {
		UUID userId = UUID.randomUUID();

		assertThatCode(() -> {
			for (int i = 0; i < 10; i++) {
				guard.check(userId);
			}
		}).doesNotThrowAnyException();

		assertThatThrownBy(() -> guard.check(userId))
				.isInstanceOfSatisfying(RateLimitedException.class, exception ->
						assertThat(exception.getRetryAfterSeconds()).isBetween(1L, 60L));
	}

	@Test
	void realRedisScriptAtomicallyCapsAConcurrentWindow() throws Exception {
		UUID userId = UUID.randomUUID();
		int requestCount = 24;
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

			assertThat(permitted).isEqualTo(10L);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void differentAccountsHaveIndependentBuckets() {
		UUID firstUser = UUID.randomUUID();
		UUID secondUser = UUID.randomUUID();
		for (int i = 0; i < 10; i++) {
			guard.check(firstUser);
		}

		assertThatThrownBy(() -> guard.check(firstUser)).isInstanceOf(RateLimitedException.class);
		assertThatCode(() -> guard.check(secondUser)).doesNotThrowAnyException();
	}

	@Test
	void corruptedPersistentCounterIsGivenAnExpiryInsteadOfLockingTheAccountForever() {
		UUID userId = UUID.randomUUID();
		String key = "soundconnect:listener-profile:playlist-rate-limit:user:" + userId;
		redisTemplate.opsForValue().set(key, "99");

		assertThatThrownBy(() -> guard.check(userId)).isInstanceOf(RateLimitedException.class);
		assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 60L);
	}
}
