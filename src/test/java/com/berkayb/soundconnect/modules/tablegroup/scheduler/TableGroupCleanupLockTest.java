package com.berkayb.soundconnect.modules.tablegroup.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TableGroupCleanupLockTest {

	@Test
	void tryAcquire_whenRedisGrantsLease_returnsToken() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

		var lease = new TableGroupCleanupLock(redis).tryAcquire();

		assertThat(lease).isPresent();
		assertThat(lease.orElseThrow().token()).isNotBlank();
	}

	@Test
	void tryAcquire_whenRedisFails_failsClosed() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.opsForValue()).thenThrow(new IllegalStateException("redis unavailable"));

		assertThat(new TableGroupCleanupLock(redis).tryAcquire()).isEmpty();
	}
}
