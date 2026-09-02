package com.berkayb.soundconnect.modules.tablegroup.chat.cache;

import com.berkayb.soundconnect.modules.tablegroup.observability.TableGroupMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stores the unread counts for one table group in a single, expiring Redis hash.
 *
 * <p>Key: {@code table-group:chat:unread:{tableGroupId}}</p>
 * <p>Field: {@code userId}, value: unread count</p>
 *
 * <p>The hash layout makes group cleanup a single {@code DEL}; it deliberately
 * avoids Redis {@code KEYS}, which is blocking and unsafe on production data
 * sets. Increment and TTL refresh are performed by one Lua script so a newly
 * created counter can never be left without an expiry.</p>
 *
 * <p>Unread counts are a derived cache. Redis outages are therefore logged and
 * degraded to a zero/missed badge instead of rolling back a persisted chat
 * message or making chat history unavailable.</p>
 */
@Component
@Slf4j
public class TableGroupChatUnreadHelper {

	static final Duration DEFAULT_UNREAD_TTL = Duration.ofDays(30);
	private static final String KEY_PREFIX = "table-group:chat:unread:";
	private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
			"local count = redis.call('HINCRBY', KEYS[1], ARGV[1], 1); "
					+ "redis.call('PEXPIRE', KEYS[1], ARGV[2]); "
					+ "return count;",
			Long.class
	);

	private final StringRedisTemplate redisTemplate;
	private final TableGroupMetrics metrics;
	private final long unreadTtlMillis;

	public TableGroupChatUnreadHelper(
			StringRedisTemplate redisTemplate,
			TableGroupMetrics metrics,
			@Value("${app.table-group.chat.unread-ttl:PT720H}") Duration unreadTtl
	) {
		this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate is required");
		this.metrics = Objects.requireNonNull(metrics, "metrics is required");
		if (unreadTtl == null || unreadTtl.isZero() || unreadTtl.isNegative()) {
			throw new IllegalArgumentException("Unread TTL must be positive");
		}
		this.unreadTtlMillis = unreadTtl.toMillis();
		if (unreadTtlMillis <= 0) {
			throw new IllegalArgumentException("Unread TTL must be at least one millisecond");
		}
	}

	public void incrementUnread(UUID userId, UUID tableGroupId) {
		Objects.requireNonNull(userId, "userId is required");
		Objects.requireNonNull(tableGroupId, "tableGroupId is required");
		try {
			redisTemplate.execute(
					INCREMENT_WITH_TTL,
					List.of(key(tableGroupId)),
					userId.toString(),
					Long.toString(unreadTtlMillis)
			);
		} catch (RuntimeException exception) {
			recordCacheFailure("increment");
			log.warn(
					"TABLE-GROUP CHAT unread increment failed: userId={}, tableGroupId={}, error={}",
					userId,
					tableGroupId,
					exception.toString()
			);
		}
	}

	public void resetUnread(UUID userId, UUID tableGroupId) {
		Objects.requireNonNull(userId, "userId is required");
		Objects.requireNonNull(tableGroupId, "tableGroupId is required");
		try {
			hashOperations().delete(key(tableGroupId), userId.toString());
		} catch (RuntimeException exception) {
			recordCacheFailure("reset");
			log.warn(
					"TABLE-GROUP CHAT unread reset failed: userId={}, tableGroupId={}, error={}",
					userId,
					tableGroupId,
					exception.toString()
			);
		}
	}

	public int getUnread(UUID userId, UUID tableGroupId) {
		Objects.requireNonNull(userId, "userId is required");
		Objects.requireNonNull(tableGroupId, "tableGroupId is required");
		String redisKey = key(tableGroupId);
		String field = userId.toString();
		try {
			String value = hashOperations().get(redisKey, field);
			if (value == null) {
				return 0;
			}

			try {
				long unread = Long.parseLong(value);
				if (unread <= 0) {
					hashOperations().delete(redisKey, field);
					return 0;
				}
				return unread > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) unread;
			} catch (NumberFormatException exception) {
				recordCacheFailure("corrupt_value");
				removeCorruptCounter(redisKey, field, userId, tableGroupId, value);
				return 0;
			}
		} catch (RuntimeException exception) {
			recordCacheFailure("read");
			log.warn(
					"TABLE-GROUP CHAT unread read failed: userId={}, tableGroupId={}, error={}",
					userId,
					tableGroupId,
					exception.toString()
			);
			return 0;
		}
	}

	public void clearAllUnreadForTableGroup(UUID tableGroupId) {
		Objects.requireNonNull(tableGroupId, "tableGroupId is required");
		try {
			redisTemplate.delete(key(tableGroupId));
		} catch (RuntimeException exception) {
			recordCacheFailure("clear_group");
			log.warn(
					"TABLE-GROUP CHAT unread group cleanup failed: tableGroupId={}, error={}",
					tableGroupId,
					exception.toString()
			);
		}
	}

	private HashOperations<String, String, String> hashOperations() {
		return redisTemplate.opsForHash();
	}

	private String key(UUID tableGroupId) {
		// Curly braces keep this group together if Redis Cluster is enabled later.
		return KEY_PREFIX + "{" + tableGroupId + "}";
	}

	private void removeCorruptCounter(
			String redisKey,
			String field,
			UUID userId,
			UUID tableGroupId,
			String value
	) {
		try {
			hashOperations().delete(redisKey, field);
		} catch (RuntimeException cleanupException) {
			recordCacheFailure("corrupt_cleanup");
			log.warn(
					"TABLE-GROUP CHAT corrupt unread cleanup failed: userId={}, tableGroupId={}, error={}",
					userId,
					tableGroupId,
					cleanupException.toString()
			);
		}
		log.warn(
				"TABLE-GROUP CHAT corrupt unread counter discarded: userId={}, tableGroupId={}, value={}",
				userId,
				tableGroupId,
				value
		);
	}

	private void recordCacheFailure(String operation) {
		try {
			metrics.unreadCacheFailed(operation);
		} catch (RuntimeException metricsException) {
			log.debug(
					"TABLE-GROUP CHAT unread failure metric could not be recorded: operation={}, error={}",
					operation,
					metricsException.toString()
			);
		}
	}
}
