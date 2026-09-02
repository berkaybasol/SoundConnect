package com.berkayb.soundconnect.modules.tablegroup.abuse;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TableGroupRateLimitGuardTest {

	private StringRedisTemplate redis;
	private TableGroupRateLimitProperties properties;
	private TableGroupRateLimitGuard guard;

	@BeforeEach
	void setUp() {
		redis = mock(StringRedisTemplate.class);
		properties = new TableGroupRateLimitProperties();
		guard = new TableGroupRateLimitGuard(redis, properties);
	}

	@Test
	void permitsRequestsWithinConfiguredWindow() {
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

		assertThatCode(() -> guard.checkCreate(UUID.randomUUID())).doesNotThrowAnyException();
	}

	@Test
	void blocksRequestsPastLimitWithStableError() {
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(11L);

		assertThatThrownBy(() -> guard.checkCreate(UUID.randomUUID()))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.TABLE_GROUP_RATE_LIMITED));
	}

	@Test
	void failsClosedWhenRedisCannotEnforceProtection() {
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenThrow(new RedisConnectionFailureException("offline"));

		assertThatThrownBy(() -> guard.checkJoin(UUID.randomUUID()))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.TABLE_GROUP_RATE_LIMIT_UNAVAILABLE));
	}

	@Test
	void failsClosedWhenRedisScriptReturnsNoResult() {
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);

		assertThatThrownBy(() -> guard.checkJoin(UUID.randomUUID()))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.TABLE_GROUP_RATE_LIMIT_UNAVAILABLE));
	}

	@Test
	void gameReadUsesOnlyTheRequesterScopedBucket() {
		UUID userId = UUID.randomUUID();
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

		guard.checkGameRead(userId);

		verify(redis).execute(
				any(RedisScript.class),
				eq(List.of("soundconnect:table-group:rate-limit:game:read:user:" + userId)),
				any(Object[].class)
		);
		verifyNoMoreInteractions(redis);
	}

	@Test
	void messageUserAndTableBucketsAreChargedOnlyByTheirDedicatedMethods() {
		UUID userId = UUID.randomUUID();
		UUID tableId = UUID.randomUUID();
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

		guard.checkMessageUser(userId, tableId);

		verify(redis).execute(
				any(RedisScript.class),
				eq(List.of("soundconnect:table-group:rate-limit:message:user:" + userId)),
				any(Object[].class)
		);
		verify(redis).execute(
				any(RedisScript.class),
				eq(List.of("soundconnect:table-group:rate-limit:message:user:" + userId + ":table:" + tableId)),
				any(Object[].class)
		);
		verify(redis, never()).execute(
				any(RedisScript.class),
				eq(List.of("soundconnect:table-group:rate-limit:message:table:" + tableId)),
				any(Object[].class)
		);

		guard.checkMessageTable(tableId);

		verify(redis).execute(
				any(RedisScript.class),
				eq(List.of("soundconnect:table-group:rate-limit:message:table:" + tableId)),
				any(Object[].class)
		);
	}

	@Test
	void gameUserAndResourceBucketsAreIndependent() {
		UUID userId = UUID.randomUUID();
		UUID tableId = UUID.randomUUID();
		UUID gameId = UUID.randomUUID();
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

		guard.checkGameCreateUser(userId);
		guard.checkGameCommandUser(userId);

		verify(redis, never()).execute(
				any(RedisScript.class),
				eq(List.of("soundconnect:table-group:rate-limit:game:create:table:" + tableId)),
				any(Object[].class)
		);
		verify(redis, never()).execute(
				any(RedisScript.class),
				eq(List.of("soundconnect:table-group:rate-limit:game:command:game:" + gameId)),
				any(Object[].class)
		);

		guard.checkGameCreateTable(tableId);
		guard.checkGameCommandGame(gameId);

		verify(redis).execute(
				any(RedisScript.class),
				eq(List.of("soundconnect:table-group:rate-limit:game:create:table:" + tableId)),
				any(Object[].class)
		);
		verify(redis).execute(
				any(RedisScript.class),
				eq(List.of("soundconnect:table-group:rate-limit:game:command:game:" + gameId)),
				any(Object[].class)
		);
	}
}
