package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MusicianCalendarRateLimitGuardTest {
	private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
	private final MusicianCalendarRateLimitProperties properties = new MusicianCalendarRateLimitProperties();
	private final MusicianCalendarRateLimitGuard guard = new MusicianCalendarRateLimitGuard(redis, properties);

	@Test
	void defaultPolicyIsSmallShortAndValidated() {
		assertThat(properties.getBurstCapacity()).isEqualTo(3);
		assertThat(properties.getRefillPeriod()).isEqualTo(Duration.ofSeconds(10));
		try (var factory = Validation.buildDefaultValidatorFactory()) {
			assertThat(factory.getValidator().validate(properties)).isEmpty();
			properties.setBurstCapacity(100);
			properties.setRefillPeriod(Duration.ofMillis(1));
			assertThat(factory.getValidator().validate(properties)).hasSize(2);
		}
	}

	@Test
	void disabledPolicyDoesNotContactRedis() {
		properties.setEnabled(false);
		guard.check(UUID.randomUUID());
		verifyNoInteractions(redis);
	}

	@Test
	void allowedResultIsAccepted() {
		when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);
		assertThatCode(() -> guard.check(UUID.randomUUID())).doesNotThrowAnyException();
	}

	@Test
	void limitedResponseRoundsUpRetryAfterAndPreservesDomainCode() {
		when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1001L);
		assertThatThrownBy(() -> guard.check(UUID.randomUUID())).isInstanceOfSatisfying(RateLimitedException.class, e -> {
			assertThat(e.getRetryAfterSeconds()).isEqualTo(2);
			assertThat(e.getErrorType()).isEqualTo(ErrorType.MUSICIAN_CALENDAR_RATE_LIMITED);
		});
	}

	@Test
	void redisOutageFailsClosedWithShortRetry() {
		when(redis.execute(any(RedisScript.class), anyList(), any(), any())).thenThrow(new IllegalStateException("offline"));
		assertUnavailable();
	}

	@Test
	void missingRedisReplyFailsClosed() {
		assertUnavailable();
	}

	private void assertUnavailable() {
		assertThatThrownBy(() -> guard.check(UUID.randomUUID())).isInstanceOfSatisfying(ServiceUnavailableRetryException.class, e -> {
			assertThat(e.getRetryAfterSeconds()).isEqualTo(5);
			assertThat(e.getErrorType()).isEqualTo(ErrorType.MUSICIAN_CALENDAR_RATE_LIMIT_UNAVAILABLE);
		});
	}
}
