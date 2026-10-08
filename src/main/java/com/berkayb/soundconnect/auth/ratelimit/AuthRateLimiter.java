package com.berkayb.soundconnect.auth.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthRateLimiter {

	// Count and expiry are read atomically. There is no second Redis round trip
	// that could observe a different window; the template owns the connection.
	private static final DefaultRedisScript<List> INCREMENT_WITH_EXPIRY = new DefaultRedisScript<>(
			"local current = redis.call('INCR', KEYS[1]); "
					+ "local ttl = redis.call('TTL', KEYS[1]); "
					+ "if ttl < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]); ttl = tonumber(ARGV[1]); end; "
					+ "return {current, ttl};",
			List.class
	);
	private static final long REDIS_WARNING_INTERVAL_MILLIS = 60_000L;
	private static final long UNAVAILABLE_RETRY_AFTER_SECONDS = 5L;

	private final StringRedisTemplate redisTemplate;
	private final AuthRateLimitProperties properties;
	private final AtomicLong lastRedisWarningAt = new AtomicLong(0L);

	public Decision check(String bucket, String clientAddress, AuthRateLimitProperties.Policy policy) {
		return checkHashedIdentity(bucket, "ip", hashIdentity(clientAddress), policy);
	}

	/**
	 * Applies the same atomic policy to an account identifier. Only its SHA-256
	 * digest is used in the Redis key; raw usernames, emails and provider subjects
	 * are never persisted or logged by the limiter.
	 */
	public Decision checkAccount(String bucket, String accountIdentifier, AuthRateLimitProperties.Policy policy) {
		return checkHashedIdentity(bucket, "account", hashIdentity(accountIdentifier), policy);
	}

	private Decision checkHashedIdentity(
			String bucket,
			String dimension,
			String identityDigest,
			AuthRateLimitProperties.Policy policy
	) {
		if (!properties.isEnabled()) {
			return Decision.permit();
		}

		long windowSeconds = policy.getWindow().toSeconds();
		String key = properties.getKeyPrefix()
				+ ":" + bucket
				+ ":" + dimension
				+ ":" + identityDigest;

		try {
			List<?> result = redisTemplate.execute(
					INCREMENT_WITH_EXPIRY,
					List.of(key),
					Long.toString(windowSeconds)
			);
			if (result == null || result.size() != 2
					|| !(result.get(0) instanceof Long count) || count <= 0
					|| !(result.get(1) instanceof Long ttl) || ttl < 0) {
				throw new IllegalStateException("Redis authentication limiter returned an invalid result");
			}
			if (count <= policy.getLimit()) {
				return Decision.permit();
			}

			return Decision.block(Math.max(1L, Math.min(windowSeconds, ttl)));
		} catch (RuntimeException exception) {
			// Readiness removes unhealthy nodes asynchronously. The request guard
			// must remain closed during that interval and for direct/internal calls.
			warnRedisFailureOncePerInterval(exception);
			return Decision.unavailable();
		}
	}

	private void warnRedisFailureOncePerInterval(RuntimeException exception) {
		long now = System.currentTimeMillis();
		long previous = lastRedisWarningAt.get();
		if (now - previous >= REDIS_WARNING_INTERVAL_MILLIS
				&& lastRedisWarningAt.compareAndSet(previous, now)) {
			log.warn("Authentication rate limiter unavailable; requests are rejected. exceptionType={}",
					exception.getClass().getSimpleName());
		}
	}

	private static String hashIdentity(String identity) {
		String normalized = identity == null || identity.isBlank()
				? "unknown"
				: identity.trim();
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(normalized.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	public record Decision(Status status, long retryAfterSeconds) {
		public boolean allowed() {
			return status == Status.PERMITTED;
		}

		public static Decision permit() {
			return new Decision(Status.PERMITTED, 0L);
		}

		public static Decision block(long retryAfterSeconds) {
			return new Decision(Status.LIMITED, retryAfterSeconds);
		}

		public static Decision unavailable() {
			return new Decision(Status.UNAVAILABLE, UNAVAILABLE_RETRY_AFTER_SECONDS);
		}
	}

	public enum Status {
		PERMITTED, LIMITED, UNAVAILABLE
	}
}
