package com.berkayb.soundconnect.modules.tablegroup.chat.cache;

import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TableGroupChatUnreadHelperTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private HashOperations<String, String, String> hashOperations;

	@Mock
	private TableGroupMetrics metrics;

	private TableGroupChatUnreadHelper unreadHelper;

	@BeforeEach
	void setUp() {
		unreadHelper = new TableGroupChatUnreadHelper(
				redisTemplate,
				metrics,
				TableGroupChatUnreadHelper.DEFAULT_UNREAD_TTL
		);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void incrementUnread_shouldAtomicallyIncrementHashAndRefreshTtl() {
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		String expectedKey = key(tableGroupId);

		unreadHelper.incrementUnread(userId, tableGroupId);

		ArgumentCaptor<RedisScript> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
		verify(redisTemplate).execute(
				scriptCaptor.capture(),
				eq(List.of(expectedKey)),
				eq(userId.toString()),
				eq(Long.toString(TableGroupChatUnreadHelper.DEFAULT_UNREAD_TTL.toMillis()))
		);
		assertThat(scriptCaptor.getValue().getScriptAsString())
				.contains("HINCRBY")
				.contains("PEXPIRE");
	}

	@Test
	void incrementUnread_whenRedisFails_shouldNotBreakChatDelivery() {
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
				.thenThrow(new IllegalStateException("redis unavailable"));

		assertThatCode(() -> unreadHelper.incrementUnread(userId, tableGroupId))
				.doesNotThrowAnyException();
		verify(metrics).unreadCacheFailed("increment");
	}

	@Test
	void resetUnread_shouldDeleteOnlyUsersHashField() {
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);

		unreadHelper.resetUnread(userId, tableGroupId);

		verify(hashOperations).delete(key(tableGroupId), userId.toString());
	}

	@Test
	void getUnread_whenValueExists_shouldReturnParsedInteger() {
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
		when(hashOperations.get(key(tableGroupId), userId.toString())).thenReturn("7");

		assertThat(unreadHelper.getUnread(userId, tableGroupId)).isEqualTo(7);
	}

	@Test
	void getUnread_whenValueIsMissing_shouldReturnZero() {
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);

		assertThat(unreadHelper.getUnread(userId, tableGroupId)).isZero();
	}

	@Test
	void getUnread_whenCounterExceedsApiRange_shouldClampToIntegerMax() {
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
		when(hashOperations.get(key(tableGroupId), userId.toString()))
				.thenReturn(Long.toString((long) Integer.MAX_VALUE + 50L));

		assertThat(unreadHelper.getUnread(userId, tableGroupId)).isEqualTo(Integer.MAX_VALUE);
	}

	@Test
	void getUnread_whenCounterIsCorrupt_shouldDeleteFieldAndReturnZero() {
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		when(redisTemplate.<String, String>opsForHash()).thenReturn(hashOperations);
		when(hashOperations.get(key(tableGroupId), userId.toString())).thenReturn("not-a-number");

		assertThat(unreadHelper.getUnread(userId, tableGroupId)).isZero();
		verify(hashOperations).delete(key(tableGroupId), userId.toString());
		verify(metrics).unreadCacheFailed("corrupt_value");
	}

	@Test
	void getUnread_whenRedisFails_shouldReturnZero() {
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		when(redisTemplate.<String, String>opsForHash()).thenThrow(new IllegalStateException("redis unavailable"));

		assertThat(unreadHelper.getUnread(userId, tableGroupId)).isZero();
		verify(metrics).unreadCacheFailed("read");
	}

	@Test
	void clearAllUnreadForTableGroup_shouldDeleteSingleHashWithoutKeysScan() {
		UUID tableGroupId = UUID.randomUUID();

		unreadHelper.clearAllUnreadForTableGroup(tableGroupId);

		verify(redisTemplate).delete(key(tableGroupId));
		verify(redisTemplate, never()).keys(anyString());
	}

	@Test
	void constructor_whenTtlIsNotPositive_shouldFailFast() {
		assertThatThrownBy(() -> new TableGroupChatUnreadHelper(redisTemplate, metrics, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("TTL");
	}

	private String key(UUID tableGroupId) {
		return "table-group:chat:unread:{" + tableGroupId + "}";
	}
}
