package com.berkayb.soundconnect.auth.otp.service;

import com.berkayb.soundconnect.shared.config.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers(disabledWithoutDocker = true)
@DataRedisTest
@Import({OtpService.class, RedisConfig.class})
@TestPropertySource(properties = {
		"otp.ttl.minutes=1",
		"otp.length=6",
		"otp.max-attempt=5",
		"otp.resend.cooldown.seconds=1"
})
class OtpServiceRedisIT {

	@Container
	static final GenericContainer<?> REDIS =
			new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("spring.data.redis.ssl.enabled", () -> false);
	}

	@Autowired OtpService otpService;
	@Autowired RedisTemplate<String, String> redisTemplate;

	@BeforeEach
	void flushRedis() {
		redisTemplate.getConnectionFactory()
				.getConnection()
				.serverCommands()
				.flushAll();
	}

	@Test
	void passwordResetAndRegistrationCodesArePurposeIsolated() {
		OtpService.OtpIssueClaim registration =
				otpService.acquireInitialOtp("user@example.com");
		OtpService.OtpIssueClaim passwordReset =
				otpService.acquirePasswordResetOtp("user@example.com");

		assertThat(registration.acquired()).isTrue();
		assertThat(passwordReset.acquired()).isTrue();
		assertThat(otpService.verifyOtp("user@example.com", registration.code())).isTrue();
		assertThat(otpService.verifyPasswordResetOtp(
				"user@example.com", passwordReset.code())).isTrue();
	}

	@Test
	void passwordResetCodeCanBeConsumedByOnlyOneConcurrentRequest() throws Exception {
		OtpService.OtpIssueClaim claim =
				otpService.acquirePasswordResetOtp("user@example.com");
		int workers = 16;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(workers);
		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int index = 0; index < workers; index++) {
				results.add(executor.submit(() -> {
					start.await();
					return otpService.verifyPasswordResetOtp(
							"user@example.com", claim.code());
				}));
			}

			start.countDown();
			long successes = 0;
			for (Future<Boolean> result : results) {
				if (result.get()) {
					successes++;
				}
			}
			assertThat(successes).isEqualTo(1L);
			assertThat(otpService.verifyPasswordResetOtp(
					"user@example.com", claim.code())).isFalse();
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void attemptLimitAndExpiryBothInvalidatePasswordResetCode() {
		OtpService.OtpIssueClaim exhausted =
				otpService.acquirePasswordResetOtp("attempts@example.com");
		for (int attempt = 0; attempt < 5; attempt++) {
			assertThat(otpService.verifyPasswordResetOtp(
					"attempts@example.com", "000000")).isFalse();
		}
		assertThat(otpService.verifyPasswordResetOtp(
				"attempts@example.com", exhausted.code())).isFalse();

		OtpService.OtpIssueClaim expiring =
				otpService.acquirePasswordResetOtp("expiry@example.com");
		Set<String> keys = redisTemplate.keys(
				"soundconnect:otp:*:password-reset:code");
		assertThat(keys).hasSize(1);
		String key = keys.iterator().next();
		redisTemplate.expire(key, Duration.ofMillis(10));

		await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
				assertThat(redisTemplate.hasKey(key)).isFalse());
		assertThat(otpService.verifyPasswordResetOtp(
				"expiry@example.com", expiring.code())).isFalse();
	}

	@Test
	void synchronousDeliveryFailureCanCancelOnlyItsOwnOtpAndCooldownClaim() {
		OtpService.OtpIssueClaim claim =
				otpService.acquirePasswordResetOtp("user@example.com");
		String wrongCode = claim.code().equals("000000") ? "000001" : "000000";

		assertThat(otpService.cancelPasswordResetOtpIssue(
				"user@example.com", wrongCode)).isFalse();
		assertThat(otpService.acquirePasswordResetOtp(
				"user@example.com").acquired()).isFalse();

		assertThat(otpService.cancelPasswordResetOtpIssue(
				"user@example.com", claim.code())).isTrue();
		assertThat(otpService.verifyPasswordResetOtp(
				"user@example.com", claim.code())).isFalse();
		assertThat(otpService.acquirePasswordResetOtp(
				"user@example.com").acquired()).isTrue();
	}
}
