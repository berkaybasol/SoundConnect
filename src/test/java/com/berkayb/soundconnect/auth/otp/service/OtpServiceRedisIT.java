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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@DataRedisTest(properties = {
		"spring.config.location=classpath:/application-test.yml", "spring.config.import=",
		// This fixture exercises real Redis; general H2 defaults disable its auto-configuration.
		"spring.autoconfigure.exclude=", "spring.data.redis.client-type=lettuce"
})
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
		String wrongCode = exhausted.code().equals("000000") ? "000001" : "000000";
		for (int attempt = 0; attempt < 5; attempt++) {
			assertThat(otpService.verifyPasswordResetOtp(
					"attempts@example.com", wrongCode)).isFalse();
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
		String wrongGeneration = java.util.UUID.randomUUID().toString();

		assertThat(otpService.cancelPasswordResetOtpIssue(
				"user@example.com", wrongGeneration)).isFalse();
		assertThat(otpService.acquirePasswordResetOtp(
				"user@example.com").acquired()).isFalse();

		assertThat(otpService.cancelPasswordResetOtpIssue(
				"user@example.com", claim.generationId())).isTrue();
		assertThat(otpService.verifyPasswordResetOtp(
				"user@example.com", claim.code())).isFalse();
		assertThat(otpService.acquirePasswordResetOtp(
				"user@example.com").acquired()).isTrue();
	}

	@Test
	void sameNumericCodeReissuedHasNewGenerationAndOldCancellationCannotDeleteIt() {
		SecureRandom deterministic = mock(SecureRandom.class);
		when(deterministic.nextInt(org.mockito.ArgumentMatchers.anyInt())).thenReturn(123456);
		Object original = ReflectionTestUtils.getField(otpService, "secureRandom");
		ReflectionTestUtils.setField(otpService, "secureRandom", deterministic);
		try {
			var old = otpService.acquirePasswordResetOtp("same-code@example.invalid");
			await().atMost(Duration.ofSeconds(3)).until(() -> redisTemplate.keys("soundconnect:otp:*:password-reset:resend-guard").isEmpty());
			var current = otpService.acquirePasswordResetOtp("same-code@example.invalid");
			assertThat(old.code().equals(current.code())).isTrue();
			assertThat(old.generationId()).isNotEqualTo(current.generationId());
			assertThat(otpService.authorizePasswordResetMail("same-code@example.invalid", old.generationId(), old.expiresAtEpochMillis())).isFalse();
			assertThat(otpService.cancelPasswordResetOtpIssue("same-code@example.invalid", old.generationId())).isFalse();
			assertThat(otpService.authorizePasswordResetMail("same-code@example.invalid", current.generationId(), current.expiresAtEpochMillis())).isTrue();
			assertThat(otpService.verifyPasswordResetOtp("same-code@example.invalid", current.code())).isTrue();
		} finally { ReflectionTestUtils.setField(otpService, "secureRandom", original); }
	}

	@Test
	void authorizationDoesNotConsumeChangeAttemptsOrExtendEitherTtl() {
		var claim = otpService.acquirePasswordResetOtp("authorize@example.invalid");
		String codeKey = redisTemplate.keys("soundconnect:otp:*:password-reset:code").iterator().next();
		String guardKey = redisTemplate.keys("soundconnect:otp:*:password-reset:resend-guard").iterator().next();
		String wrong = claim.code().equals("000000") ? "000001" : "000000";
		assertThat(otpService.verifyPasswordResetOtp("authorize@example.invalid", wrong)).isFalse();
		String stateBefore = redisTemplate.opsForValue().get(codeKey);
		long codeTtl = redisTemplate.getExpire(codeKey, java.util.concurrent.TimeUnit.MILLISECONDS);
		long guardTtl = redisTemplate.getExpire(guardKey, java.util.concurrent.TimeUnit.MILLISECONDS);
		for (int i = 0; i < 3; i++) assertThat(otpService.authorizePasswordResetMail("authorize@example.invalid", claim.generationId(), claim.expiresAtEpochMillis())).isTrue();
		assertThat(stateBefore.equals(redisTemplate.opsForValue().get(codeKey))).isTrue();
		assertThat(redisTemplate.getExpire(codeKey, java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L, codeTtl);
		assertThat(redisTemplate.getExpire(guardKey, java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L, guardTtl);
		assertThat(otpService.authorizePasswordResetMail("sibling@example.invalid", claim.generationId(), claim.expiresAtEpochMillis())).isFalse();
		assertThat(otpService.authorizePasswordResetMail("authorize@example.invalid", "malformed", claim.expiresAtEpochMillis())).isFalse();
		assertThat(otpService.authorizePasswordResetMail("authorize@example.invalid", null, claim.expiresAtEpochMillis())).isFalse();
		assertThat(otpService.authorizePasswordResetMail("authorize@example.invalid", claim.generationId(), claim.expiresAtEpochMillis() + 1)).isFalse();
		assertThat(otpService.verifyPasswordResetOtp("authorize@example.invalid", claim.code())).isTrue();
		assertThat(otpService.authorizePasswordResetMail("authorize@example.invalid", claim.generationId(), claim.expiresAtEpochMillis())).isFalse();
	}

	@Test
	void serverAbsoluteExpiryAndAttemptBudgetAreBothEnforced() {
		var claim = otpService.acquirePasswordResetOtp("expired@example.invalid");
		String key = redisTemplate.keys("soundconnect:otp:*:password-reset:code").iterator().next();
		// Deliberately retain Redis TTL while changing only this disposable fixture's deadline.
		redisTemplate.opsForValue().set(key, claim.code() + ":0:" + claim.generationId() + ":1", Duration.ofSeconds(60));
		assertThat(otpService.authorizePasswordResetMail("expired@example.invalid", claim.generationId(), 1L)).isFalse();
		var budget = otpService.acquirePasswordResetOtp("budget@example.invalid");
		String wrong = budget.code().equals("000000") ? "000001" : "000000";
		for (int i = 0; i < 5; i++) assertThat(otpService.verifyPasswordResetOtp("budget@example.invalid", wrong)).isFalse();
		assertThat(otpService.authorizePasswordResetMail("budget@example.invalid", budget.generationId(), budget.expiresAtEpochMillis())).isFalse();
	}

	@Test
	void authorizationLinearizesBeforeNewIssueButProviderIoDoesNotLockTheClaim() throws Exception {
		var old = otpService.acquirePasswordResetOtp("linearize@example.invalid");
		var gate = org.mockito.Mockito.spy(otpService);
		var authorized = new CountDownLatch(1);
		var proceedToProvider = new CountDownLatch(1);
		org.mockito.Mockito.doAnswer(invocation -> {
			Object decision = invocation.callRealMethod();
			authorized.countDown();
			assertThat(proceedToProvider.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			return decision;
		}).when(gate).authorizePasswordResetMail(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
		var sender = mock(com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient.class);
		var helper = mock(com.berkayb.soundconnect.shared.mail.helper.MailJobHelper.class);
		when(helper.acquireLock(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
		var consumer = new com.berkayb.soundconnect.shared.mail.consumer.MailJobConsumer(sender, helper,
				mock(com.berkayb.soundconnect.shared.mail.producer.MailRetryPublisher.class),
				mock(com.berkayb.soundconnect.modules.notification.service.NotificationMailDelivery.class));
		consumer.setOtpService(gate);
		var job = new com.berkayb.soundconnect.shared.mail.dto.MailSendRequest("linearize@example.invalid", "reset", "redacted", "redacted",
				com.berkayb.soundconnect.shared.mail.enums.MailKind.PASSWORD_RESET,
				java.util.Map.of("requestId", old.generationId(), "claimRecipient", "linearize@example.invalid", "expiresAtEpochMillis", old.expiresAtEpochMillis()));
		try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
			Future<?> delivery = workers.submit(() -> consumer.listenMailJobs(job, 1L, java.util.Map.of(), mock(com.rabbitmq.client.Channel.class)));
			assertThat(authorized.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			org.mockito.Mockito.verifyNoInteractions(sender);
			await().atMost(Duration.ofSeconds(3)).until(() -> redisTemplate.keys("soundconnect:otp:*:password-reset:resend-guard").isEmpty());
			var current = otpService.acquirePasswordResetOtp("linearize@example.invalid");
			assertThat(current.acquired()).isTrue();
			proceedToProvider.countDown();
			delivery.get(5, java.util.concurrent.TimeUnit.SECONDS);
			org.mockito.Mockito.verify(sender).send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
			assertThat(otpService.authorizePasswordResetMail("linearize@example.invalid", old.generationId(), old.expiresAtEpochMillis())).isFalse();
			assertThat(otpService.verifyPasswordResetOtp("linearize@example.invalid", current.code())).isTrue();
		} finally { proceedToProvider.countDown(); }
	}
}
