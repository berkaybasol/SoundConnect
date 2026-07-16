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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuthRateLimiter {

	private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRY = new DefaultRedisScript<>(
			"local current = redis.call('INCR', KEYS[1]); "
					+ "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; "
					+ "return current;",
			Long.class
	);
	private static final long REDIS_WARNING_INTERVAL_MILLIS = 60_000L;

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
			Long count = redisTemplate.execute(
					INCREMENT_WITH_EXPIRY,
					List.of(key),
					Long.toString(windowSeconds)
			);
			if (count == null || count <= policy.getLimit()) {
				return Decision.permit();
			}

			Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
			long retryAfterSeconds = ttl == null || ttl <= 0 ? windowSeconds : ttl;
			return Decision.block(Math.max(1L, retryAfterSeconds));
		} catch (RuntimeException exception) {
			warnRedisFailureOncePerInterval(exception);
			return Decision.permit();
		}
	}

	private void warnRedisFailureOncePerInterval(RuntimeException exception) {
		long now = System.currentTimeMillis();
		long previous = lastRedisWarningAt.get();
		if (now - previous >= REDIS_WARNING_INTERVAL_MILLIS
				&& lastRedisWarningAt.compareAndSet(previous, now)) {
			log.warn("Authentication rate limiter unavailable; requests are allowed. exceptionType={}",
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

	public record Decision(boolean allowed, long retryAfterSeconds) {
		public static Decision permit() {
			return new Decision(true, 0L);
		}

		public static Decision block(long retryAfterSeconds) {
			return new Decision(false, retryAfterSeconds);
		}
	}
}
