package com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListenerPlaylistRateLimitGuardTest {

	private StringRedisTemplate redis;
	private ListenerPlaylistRateLimitProperties properties;
	private ListenerPlaylistRateLimitGuard guard;

	@BeforeEach
	void setUp() {
		redis = mock(StringRedisTemplate.class);
		properties = new ListenerPlaylistRateLimitProperties();
		guard = new ListenerPlaylistRateLimitGuard(redis, properties);
	}

	@Test
	void permitsRequestInsideTheWindowLimit() {
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

		assertThatCode(() -> guard.check(UUID.randomUUID())).doesNotThrowAnyException();
	}

	@Test
	void blocksWithFriendlyErrorAndRoundedRetryAfter() {
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1_001L);

		RateLimitedException exception = org.assertj.core.api.Assertions.catchThrowableOfType(
				() -> guard.check(UUID.randomUUID()), RateLimitedException.class);

		assertThat(exception.getErrorType()).isEqualTo(ErrorType.LISTENER_PLAYLIST_RATE_LIMITED);
		assertThat(exception.getRetryAfterSeconds()).isEqualTo(2L);
	}

	@Test
	void usesAStableAccountBucketAndConfiguredFixedWindow() {
		UUID userId = UUID.randomUUID();
		properties.setLimit(7);
		properties.setWindow(Duration.ofMinutes(2));
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

		guard.check(userId);

		verify(redis).execute(
				any(RedisScript.class),
				eq(List.of("soundconnect:listener-profile:playlist-rate-limit:user:" + userId)),
				eq("7"),
				eq("120000")
		);
	}

	@Test
	void failsClosedWhenRedisCannotProtectSpotify() {
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenThrow(new RedisConnectionFailureException("offline"));

		assertThatThrownBy(() -> guard.check(UUID.randomUUID()))
				.isInstanceOfSatisfying(ServiceUnavailableRetryException.class, exception -> {
					assertThat(exception.getErrorType())
							.isEqualTo(ErrorType.LISTENER_PLAYLIST_RATE_LIMIT_UNAVAILABLE);
					assertThat(exception.getRetryAfterSeconds()).isEqualTo(5L);
				});
	}

	@Test
	void failsClosedWhenRedisScriptReturnsNoResult() {
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

		assertThatThrownBy(() -> guard.check(UUID.randomUUID()))
				.isInstanceOfSatisfying(ServiceUnavailableRetryException.class, exception ->
						assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.LISTENER_PLAYLIST_RATE_LIMIT_UNAVAILABLE));
	}

	@Test
	void disabledProtectionDoesNotCallRedis() {
		properties.setEnabled(false);

		guard.check(UUID.randomUUID());

		verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}
}
