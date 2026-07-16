package com.berkayb.soundconnect.modules.media.abuse;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaUploadAbuseGuardTest {

	@Mock StringRedisTemplate redisTemplate;
	@Mock ValueOperations<String, String> valueOperations;
	@Mock ZSetOperations<String, String> zSetOperations;

	private MediaUploadGuardProperties properties;
	private MediaUploadAbuseGuard guard;

	@BeforeEach
	void setUp() {
		properties = new MediaUploadGuardProperties();
		guard = new MediaUploadAbuseGuard(redisTemplate, properties);
	}

	@Test
	void atomicallyReservesRequestBytesAndConcurrentSlot() {
		UUID userId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenReturn(List.of(0L, 0L));
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		guard.reserve(userId, assetId, 12_345L);

		ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
		verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
		assertThat(keys.getValue()).containsExactly(
				"soundconnect:media-upload:{" + userId + "}:requests",
				"soundconnect:media-upload:{" + userId + "}:bytes",
				"soundconnect:media-upload:{" + userId + "}:active"
		);
		verify(valueOperations).set(
				"soundconnect:media-upload:reservation:" + assetId,
				userId.toString(),
				Duration.ofMinutes(30)
		);
	}

	@Test
	void mapsRequestAndByteQuotaDecisionsToStable429Error() {
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenReturn(List.of(2L, 20_000L));

		assertThatThrownBy(() -> guard.reserve(UUID.randomUUID(), UUID.randomUUID(), 100L))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.MEDIA_UPLOAD_RATE_LIMITED));
	}

	@Test
	void mapsActiveReservationCapToStable429Error() {
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenReturn(List.of(3L, 20_000L));

		assertThatThrownBy(() -> guard.reserve(UUID.randomUUID(), UUID.randomUUID(), 100L))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.MEDIA_UPLOAD_CONCURRENCY_LIMITED));
	}

	@Test
	void failsClosedWhenRedisCannotProtectPresignedUploads() {
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenThrow(new RedisConnectionFailureException("offline"));

		assertThatThrownBy(() -> guard.reserve(UUID.randomUUID(), UUID.randomUUID(), 100L))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.MEDIA_UPLOAD_GUARD_UNAVAILABLE));
	}

	@Test
	void canBeExplicitlyConfiguredToFailOpen() {
		properties.setFailOpen(true);
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenThrow(new RedisConnectionFailureException("offline"));

		guard.reserve(UUID.randomUUID(), UUID.randomUUID(), 100L);

		verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}

	@Test
	void rollsBackTheActiveSlotWhenReservationMappingCannotBePersisted() {
		UUID userId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenReturn(List.of(0L, 0L));
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
		doThrow(new RedisConnectionFailureException("mapping write failed"))
				.when(valueOperations).set(any(), any(), any(Duration.class));

		assertThatThrownBy(() -> guard.reserve(userId, assetId, 100L))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.MEDIA_UPLOAD_GUARD_UNAVAILABLE));
		verify(zSetOperations).remove(
				"soundconnect:media-upload:{" + userId + "}:active",
				assetId.toString()
		);
	}
}
