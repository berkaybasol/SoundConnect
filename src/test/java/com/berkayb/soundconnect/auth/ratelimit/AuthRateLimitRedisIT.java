package com.berkayb.soundconnect.auth.ratelimit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** All fault injection targets this disposable container, never the application's Redis. */
@Testcontainers(disabledWithoutDocker = true)
@Timeout(30)
class AuthRateLimitRedisIT {

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
			.withExposedPorts(6379);

	private LettuceConnectionFactory connection;
	private StringRedisTemplate redis;
	private AuthRateLimitProperties properties;
	private AuthRateLimiter limiter;

	@BeforeEach
	void setUp() {
		var configuration = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
		var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300))
				.shutdownTimeout(Duration.ofMillis(100)).build();
		connection = new LettuceConnectionFactory(configuration, client);
		connection.afterPropertiesSet();
		redis = new StringRedisTemplate(connection);
		properties = new AuthRateLimitProperties();
		properties.setKeyPrefix("auth-limiter-it:" + UUID.randomUUID());
		limiter = new AuthRateLimiter(redis, properties);
	}

	@AfterEach
	void releaseConnections() {
		if (connection != null) connection.destroy();
	}

	@Test
	void sharedInstancesEnforceOneAtomicQuotaAndKeepEndpointIpAndAccountDimensionsIndependent() throws Exception {
		var policy = new AuthRateLimitProperties.Policy(3, Duration.ofSeconds(20));
		var otherInstance = new AuthRateLimiter(redis, properties);
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(8)) {
			List<Future<AuthRateLimiter.Decision>> calls = new ArrayList<>();
			for (int index = 0; index < 12; index++) {
				var instance = index % 2 == 0 ? limiter : otherInstance;
				calls.add(executor.submit(() -> {
					start.await();
					return instance.checkAccount("login", "shared-identity", policy);
				}));
			}
			start.countDown();
			int accepted = 0;
			for (var call : calls) {
				var result = call.get(10, TimeUnit.SECONDS);
				if (result.allowed()) accepted++;
				else {
					assertThat(result.status()).isEqualTo(AuthRateLimiter.Status.LIMITED);
					assertThat(result.retryAfterSeconds()).isBetween(1L, 20L);
				}
			}
			assertThat(accepted).isEqualTo(3);
		}
		assertThat(limiter.checkAccount("login", "different-account", policy).allowed()).isTrue();
		assertThat(limiter.checkAccount("register", "shared-identity", policy).allowed()).isTrue();
		assertThat(limiter.check("login", "shared-identity", policy).allowed()).isTrue();
		for (int index = 0; index < 3; index++) limiter.check("login", "203.0.113.1", policy);
		assertThat(limiter.check("login", "203.0.113.1", policy).status()).isEqualTo(AuthRateLimiter.Status.LIMITED);
		assertThat(limiter.check("login", "203.0.113.2", policy).allowed()).isTrue();
		assertThat(redis.keys(properties.getKeyPrefix() + ":*"))
				.allSatisfy(key -> assertThat(key).doesNotContain("shared-identity", "different-account", "203.0.113"));
	}

	@Test
	void naturalExpiryRestoresAccessAndAnOldCounterWithoutExpiryIsBoundedWithoutResettingItsQuota() throws Exception {
		var policy = new AuthRateLimitProperties.Policy(1, Duration.ofSeconds(1));
		String key = key("login", "account", "legacy-user");
		redis.opsForValue().set(key, "9");
		assertThat(redis.getExpire(key)).isEqualTo(-1);

		var blocked = limiter.checkAccount("login", "legacy-user", policy);
		assertThat(blocked.status()).isEqualTo(AuthRateLimiter.Status.LIMITED);
		assertThat(blocked.retryAfterSeconds()).isEqualTo(1);
		assertThat(redis.opsForValue().get(key)).isEqualTo("10");
		assertThat(redis.getExpire(key)).isBetween(0L, 1L);
		await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
		assertThat(limiter.checkAccount("login", "legacy-user", policy).allowed()).isTrue();
		assertThat(limiter.checkAccount("login", "legacy-user", policy).status()).isEqualTo(AuthRateLimiter.Status.LIMITED);
	}

	@Test
	void corruptedCounterFailsClosedWithoutLeakingItsContent() throws Exception {
		String key = key("login", "account", "private-user");
		redis.opsForValue().set(key, "private malformed value", Duration.ofSeconds(2));

		var result = limiter.checkAccount("login", "private-user", properties.getLogin());

		assertThat(result).isEqualTo(AuthRateLimiter.Decision.unavailable());
		await().atMost(Duration.ofSeconds(4)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
		assertThat(limiter.checkAccount("login", "private-user", properties.getLogin()).allowed()).isTrue();
	}

	@Test
	void realRedisStallFailsClosedWithinCommandTimeoutAndRecoversWithExistingConnections() {
		assertThat(limiter.check("login", "192.0.2.10", properties.getLogin()).allowed()).isTrue();
		REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
		try {
			long started = System.nanoTime();
			assertThat(limiter.check("login", "192.0.2.10", properties.getLogin()))
					.isEqualTo(AuthRateLimiter.Decision.unavailable());
			assertThat(limiter.checkAccount("login", "private-user", properties.getLogin()))
					.isEqualTo(AuthRateLimiter.Decision.unavailable());
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
		} finally {
			REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
		}
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(limiter.check("login", "192.0.2.10", properties.getLogin()).allowed()).isTrue();
			assertThat(limiter.checkAccount("login", "private-user", properties.getLogin()).allowed()).isTrue();
		});
	}

	private String key(String bucket, String dimension, String identity) throws Exception {
		String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(identity.getBytes(StandardCharsets.UTF_8)));
		return properties.getKeyPrefix() + ":" + bucket + ":" + dimension + ":" + digest;
	}
}
