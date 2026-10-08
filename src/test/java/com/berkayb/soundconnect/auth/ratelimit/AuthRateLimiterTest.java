package com.berkayb.soundconnect.auth.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class AuthRateLimiterTest {

	@Mock StringRedisTemplate redisTemplate;

	private AuthRateLimitProperties properties;
	private AuthRateLimiter rateLimiter;

	@BeforeEach
	void setUp() {
		properties = new AuthRateLimitProperties();
		rateLimiter = new AuthRateLimiter(redisTemplate, properties);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void incrementsAnExpiringRedisKeyWithoutStoringTheRawAddress() {
		doReturn(List.of(1L, 60L)).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		AuthRateLimiter.Decision decision = rateLimiter.check(
				"login",
				"203.0.113.42",
				new AuthRateLimitProperties.Policy(10, Duration.ofMinutes(1))
		);

		ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
		verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
		assertThat(decision.allowed()).isTrue();
		assertThat(keys.getValue()).singleElement()
				.asString()
				.doesNotContain("203.0.113.42")
				.startsWith("soundconnect:auth-rate-limit:login:ip:");
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void accountDimensionNeverStoresTheRawIdentifier() {
		doReturn(List.of(1L, 300L)).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		AuthRateLimiter.Decision decision = rateLimiter.checkAccount(
				"otp-resend",
				"user@example.com",
				new AuthRateLimitProperties.Policy(3, Duration.ofMinutes(5))
		);

		ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
		verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
		assertThat(decision.allowed()).isTrue();
		assertThat(keys.getValue()).singleElement()
				.asString()
				.doesNotContain("user@example.com")
				.matches("soundconnect:auth-rate-limit:otp-resend:account:[0-9a-f]{64}");
	}

	@Test
	@SuppressWarnings("rawtypes")
	void blocksWhenTheAtomicCounterExceedsTheConfiguredLimit() {
		doReturn(List.of(4L, 47L)).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		AuthRateLimiter.Decision decision = rateLimiter.check(
				"otp-resend",
				"198.51.100.8",
				new AuthRateLimitProperties.Policy(3, Duration.ofMinutes(5))
		);

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.status()).isEqualTo(AuthRateLimiter.Status.LIMITED);
		assertThat(decision.retryAfterSeconds()).isEqualTo(47L);
		verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
		verifyNoMoreInteractions(redisTemplate);
	}

	@Test
	@SuppressWarnings("rawtypes")
	void failsClosedWhenRedisIsUnavailableAndRecoversOnTheNextHealthyRequest() {
		doThrow(new RedisConnectionFailureException("unavailable"))
				.doReturn(List.of(1L, 300L))
				.when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		AuthRateLimiter.Decision decision = rateLimiter.check(
				"register",
				"192.0.2.10",
				new AuthRateLimitProperties.Policy(5, Duration.ofMinutes(5))
		);

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.status()).isEqualTo(AuthRateLimiter.Status.UNAVAILABLE);
		assertThat(decision.retryAfterSeconds()).isEqualTo(5L);
		assertThat(rateLimiter.check("register", "192.0.2.10", properties.getRegister()).allowed()).isTrue();
	}

	@ParameterizedTest
	@MethodSource("invalidResults")
	@SuppressWarnings("rawtypes")
	void failsClosedOnMissingMalformedOrInvalidRedisResults(List<?> result) {
		doReturn(result).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		assertThat(rateLimiter.check("login", "192.0.2.10", properties.getLogin()))
				.isEqualTo(AuthRateLimiter.Decision.unavailable());
		assertThat(rateLimiter.checkAccount("login", "private-user", properties.getLogin()))
				.isEqualTo(AuthRateLimiter.Decision.unavailable());
	}

	static Stream<List<?>> invalidResults() {
		return Stream.of(null, List.of(), List.of(1L), List.of(1L, 60L, 7L),
				List.of(0L, 60L), List.of(-1L, 60L), List.of("1", 60L),
				List.of(1L, -1L), List.of(1L, -2L), List.of(1L, "60"),
				java.util.Arrays.asList(null, 60L), java.util.Arrays.asList(1L, null));
	}

	@ParameterizedTest
	@MethodSource("expiryBoundaries")
	@SuppressWarnings("rawtypes")
	void throttlingRetryIsBoundedByThePolicyWindow(long ttl) {
		doReturn(List.of(11L, ttl)).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		AuthRateLimiter.Decision decision = rateLimiter.check("login", "192.0.2.10", properties.getLogin());

		assertThat(decision.status()).isEqualTo(AuthRateLimiter.Status.LIMITED);
		assertThat(decision.retryAfterSeconds()).isEqualTo(Math.max(1L, Math.min(60L, ttl)));
	}

	static Stream<Long> expiryBoundaries() {
		return Stream.of(0L, 1L, 60L, 600L, Long.MAX_VALUE);
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	@SuppressWarnings("rawtypes")
	void warningDoesNotExposeIdentitiesRedisAddressOrExceptionMessage(CapturedOutput output) {
		doThrow(new RedisConnectionFailureException("redis://private-user:private-password@private-host"))
				.when(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));

		rateLimiter.checkAccount("login", "private@example.test", properties.getLogin());
		rateLimiter.check("login", "192.0.2.10", properties.getLogin());

		assertThat(output.getAll()).contains("requests are rejected", "exceptionType=RedisConnectionFailureException")
				.doesNotContain("private-user", "private-password", "private-host", "private@example.test", "192.0.2.10");
		assertThat(output.getAll().split("requests are rejected", -1)).hasSize(2);
	}

	@Test
	void bypassesRedisWhenRateLimitingIsDisabled() {
		properties.setEnabled(false);

		AuthRateLimiter.Decision decision = rateLimiter.check(
				"login",
				"192.0.2.11",
				properties.getLogin()
		);

		assertThat(decision.allowed()).isTrue();
		verifyNoInteractions(redisTemplate);
	}
}
