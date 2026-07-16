package com.berkayb.soundconnect.auth.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
		doReturn(1L).when(redisTemplate)
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
		doReturn(1L).when(redisTemplate)
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
		doReturn(4L).when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));
		doReturn(47L).when(redisTemplate).getExpire(any(String.class), any(TimeUnit.class));

		AuthRateLimiter.Decision decision = rateLimiter.check(
				"otp-resend",
				"198.51.100.8",
				new AuthRateLimitProperties.Policy(3, Duration.ofMinutes(5))
		);

		assertThat(decision.allowed()).isFalse();
		assertThat(decision.retryAfterSeconds()).isEqualTo(47L);
	}

	@Test
	@SuppressWarnings("rawtypes")
	void failsOpenWhenRedisIsUnavailable() {
		doThrow(new RedisConnectionFailureException("unavailable"))
				.when(redisTemplate)
				.execute(any(RedisScript.class), anyList(), any(Object[].class));

		AuthRateLimiter.Decision decision = rateLimiter.check(
				"register",
				"192.0.2.10",
				new AuthRateLimitProperties.Policy(5, Duration.ofMinutes(5))
		);

		assertThat(decision.allowed()).isTrue();
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
