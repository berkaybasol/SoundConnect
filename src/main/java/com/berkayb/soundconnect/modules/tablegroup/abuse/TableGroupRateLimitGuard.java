package com.berkayb.soundconnect.modules.tablegroup.abuse;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class TableGroupRateLimitGuard {

	private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRY = new DefaultRedisScript<>(
			"local current = redis.call('INCR', KEYS[1]); "
					+ "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; "
					+ "return current;",
			Long.class
	);

	private final StringRedisTemplate redisTemplate;
	private final TableGroupRateLimitProperties properties;

	public void checkCreate(UUID userId) {
		check("create:user:" + userId, properties.getCreate());
	}

	public void checkJoin(UUID userId) {
		check("join:user:" + userId, properties.getJoin());
	}

	public void checkMessageUser(UUID userId, UUID tableGroupId) {
		check("message:user:" + userId, properties.getMessageGlobal());
		check("message:user:" + userId + ":table:" + tableGroupId, properties.getMessagePerTable());
	}

	public void checkMessageTable(UUID tableGroupId) {
		check("message:table:" + tableGroupId, properties.getMessageTableGlobal());
	}

	public void checkGameCreateUser(UUID userId) {
		check("game:create:user:" + userId, properties.getGameCreate());
	}

	public void checkGameCreateTable(UUID tableGroupId) {
		check("game:create:table:" + tableGroupId, properties.getGameCreate());
	}

	public void checkGameCommandUser(UUID userId) {
		check("game:command:user:" + userId, properties.getGameCommand());
	}

	public void checkGameCommandGame(UUID gameId) {
		check("game:command:game:" + gameId, properties.getGameCommand());
	}

	public void checkGameRead(UUID userId) {
		check("game:read:user:" + userId, properties.getGameRead());
	}

	private void check(String bucket, TableGroupRateLimitProperties.Policy policy) {
		if (!properties.isEnabled()) {
			return;
		}
		long windowSeconds = policy.getWindow().toSeconds();
		String key = properties.getKeyPrefix() + ":" + bucket;
		try {
			Long count = redisTemplate.execute(
					INCREMENT_WITH_EXPIRY,
					List.of(key),
					Long.toString(windowSeconds)
			);
			if (count == null) {
				throw new IllegalStateException("Redis rate-limit script returned no result");
			}
			if (count <= policy.getLimit()) {
				return;
			}
			Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
			long retryAfter = ttl == null || ttl <= 0 ? windowSeconds : ttl;
			throw new RateLimitedException(ErrorType.TABLE_GROUP_RATE_LIMITED, Math.max(1L, retryAfter));
		} catch (RateLimitedException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			// Redis is part of the production readiness probe. Until the node is
			// removed from service, fail closed so an outage cannot disable abuse
			// protection for public group creation and chat.
			throw new SoundConnectException(
					ErrorType.TABLE_GROUP_RATE_LIMIT_UNAVAILABLE,
					"Table-group abuse protection is temporarily unavailable"
			);
		}
	}
}
