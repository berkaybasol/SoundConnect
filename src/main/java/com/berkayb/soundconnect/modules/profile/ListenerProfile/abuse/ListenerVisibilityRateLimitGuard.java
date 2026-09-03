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
 * Rejects abusive visibility requests before they enter the transactional
 * listener-profile service or acquire its pessimistic write lock.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListenerVisibilityRateLimitGuard {
	private static final long REDIS_WARNING_INTERVAL_MILLIS = 60_000L;
	private static final long UNAVAILABLE_RETRY_AFTER_SECONDS = 5L;

	/**
	 * Redis-server-time token bucket. A value of zero permits the request;
	 * otherwise the script returns the number of milliseconds until one token
	 * is available. Redis TIME avoids clock skew between application nodes.
	 */
	private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>(
			"local capacity = tonumber(ARGV[1]); "
					+ "local refill_ms = tonumber(ARGV[2]); "
					+ "local now_parts = redis.call('TIME'); "
					+ "local now_ms = (tonumber(now_parts[1]) * 1000) + math.floor(tonumber(now_parts[2]) / 1000); "
					+ "local state = redis.call('HMGET', KEYS[1], 'tokens', 'last_refill_ms'); "
					+ "local tokens = tonumber(state[1]); "
					+ "local last_refill_ms = tonumber(state[2]); "
					+ "if tokens == nil or last_refill_ms == nil then tokens = capacity; last_refill_ms = now_ms; end; "
					+ "tokens = math.min(capacity, math.max(0, tokens)); "
					+ "if last_refill_ms > now_ms then last_refill_ms = now_ms; end; "
					+ "if now_ms > last_refill_ms then "
					+ "local refill_count = math.floor((now_ms - last_refill_ms) / refill_ms); "
					+ "if refill_count > 0 then "
					+ "tokens = math.min(capacity, tokens + refill_count); "
					+ "last_refill_ms = last_refill_ms + (refill_count * refill_ms); "
					+ "end; end; "
					+ "local retry_after_ms = 0; "
					+ "if tokens >= 1 then tokens = tokens - 1; "
					+ "else retry_after_ms = math.max(1, refill_ms - (now_ms - last_refill_ms)); end; "
					+ "redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill_ms', last_refill_ms); "
					+ "redis.call('PEXPIRE', KEYS[1], math.max(refill_ms, capacity * refill_ms * 2)); "
					+ "return retry_after_ms;",
			Long.class
	);

	private final StringRedisTemplate redisTemplate;
	private final ListenerVisibilityRateLimitProperties properties;
	private final AtomicLong lastRedisWarningAt = new AtomicLong(0L);

	public void check(UUID userId) {
		Objects.requireNonNull(userId, "userId must not be null");
		if (!properties.isEnabled()) {
			return;
		}

		String key = properties.getKeyPrefix() + ":user:" + userId;
		try {
			long refillMillis = properties.getRefillPeriod().toMillis();
			Long retryAfterMillis = redisTemplate.execute(
					TOKEN_BUCKET,
					List.of(key),
					Integer.toString(properties.getBurstCapacity()),
					Long.toString(refillMillis)
			);
			if (retryAfterMillis == null) {
				throw new IllegalStateException("Redis visibility rate-limit script returned no result");
			}
			if (retryAfterMillis <= 0) {
				return;
			}
			long retryAfterSeconds = Math.max(1L, (retryAfterMillis + 999L) / 1000L);
			throw new RateLimitedException(
					ErrorType.LISTENER_PROFILE_VISIBILITY_RATE_LIMITED,
					retryAfterSeconds
			);
		} catch (RateLimitedException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			// Redis participates in the production readiness probe. Fail closed
			// during the brief removal window so an outage cannot expose the small
			// JDBC pool to an unbounded lock queue on this route.
			warnRedisFailureOncePerInterval(exception);
			throw new ServiceUnavailableRetryException(
					ErrorType.LISTENER_PROFILE_VISIBILITY_RATE_LIMIT_UNAVAILABLE,
					UNAVAILABLE_RETRY_AFTER_SECONDS
			);
		}
	}

	private void warnRedisFailureOncePerInterval(RuntimeException exception) {
		long now = System.currentTimeMillis();
		long previous = lastRedisWarningAt.get();
		if (now - previous >= REDIS_WARNING_INTERVAL_MILLIS
				&& lastRedisWarningAt.compareAndSet(previous, now)) {
			log.warn("Listener visibility rate limiter unavailable. exceptionType={}",
					exception.getClass().getSimpleName());
		}
	}
}
