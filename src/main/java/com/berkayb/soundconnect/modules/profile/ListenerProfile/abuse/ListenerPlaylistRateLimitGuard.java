package com.berkayb.soundconnect.modules.profile.ListenerProfile.abuse;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stops abusive playlist replacement attempts before they can fan out into as
 * many as four Spotify oEmbed requests.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListenerPlaylistRateLimitGuard {
	private static final long REDIS_WARNING_INTERVAL_MILLIS = 60_000L;
	private static final long UNAVAILABLE_RETRY_AFTER_SECONDS = 5L;

	/**
	 * Atomically increments an account-scoped fixed window and returns zero when
	 * allowed, otherwise the remaining window duration in milliseconds.
	 */
	private static final DefaultRedisScript<Long> FIXED_WINDOW = new DefaultRedisScript<>(
			"local count = redis.call('INCR', KEYS[1]); "
					+ "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]); end; "
					+ "if count <= tonumber(ARGV[1]) then return 0; end; "
					+ "local ttl = redis.call('PTTL', KEYS[1]); "
					+ "if ttl < 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]); return tonumber(ARGV[2]); end; "
					+ "return ttl;",
			Long.class
	);

	private final StringRedisTemplate redisTemplate;
	private final ListenerPlaylistRateLimitProperties properties;
	private final AtomicLong lastRedisWarningAt = new AtomicLong(0L);

	public void check(UUID userId) {
		Objects.requireNonNull(userId, "userId must not be null");
		if (!properties.isEnabled()) {
			return;
		}

		String key = properties.getKeyPrefix() + ":user:" + userId;
		try {
			long windowMillis = properties.getWindow().toMillis();
			Long retryAfterMillis = redisTemplate.execute(
					FIXED_WINDOW,
					List.of(key),
					Integer.toString(properties.getLimit()),
					Long.toString(windowMillis)
			);
			if (retryAfterMillis == null) {
				throw new IllegalStateException("Redis listener playlist rate-limit script returned no result");
			}
			if (retryAfterMillis <= 0) {
				return;
			}
			long retryAfterSeconds = Math.max(1L, (retryAfterMillis + 999L) / 1000L);
			throw new RateLimitedException(
					ErrorType.LISTENER_PLAYLIST_RATE_LIMITED,
					retryAfterSeconds
			);
		} catch (RateLimitedException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			// Redis participates in the production readiness probe. Fail closed
			// until the unhealthy instance is removed, preventing an outage from
			// becoming unbounded traffic to Spotify.
			warnRedisFailureOncePerInterval(exception);
			throw new ServiceUnavailableRetryException(
					ErrorType.LISTENER_PLAYLIST_RATE_LIMIT_UNAVAILABLE,
					UNAVAILABLE_RETRY_AFTER_SECONDS
			);
		}
	}

	private void warnRedisFailureOncePerInterval(RuntimeException exception) {
		long now = System.currentTimeMillis();
		long previous = lastRedisWarningAt.get();
		if (now - previous >= REDIS_WARNING_INTERVAL_MILLIS
				&& lastRedisWarningAt.compareAndSet(previous, now)) {
			log.warn("Listener playlist rate limiter unavailable. exceptionType={}",
					exception.getClass().getSimpleName());
		}
	}
}
