package com.berkayb.soundconnect.modules.tablegroup.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A short-lived, token-fenced fleet lock for the table-group expiry scan.
 *
 * <p>The scheduler runs on every API node. Without this lock each node can
 * expire the same rows and publish duplicate notifications. Redis failure is
 * deliberately fail-closed: active-list and chat queries already enforce the
 * expiry timestamp, so delaying lifecycle cleanup is safer than duplicating
 * externally visible side effects.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TableGroupCleanupLock {

	private static final String KEY = "table-group:cleanup:expiry-lock";
	private static final Duration LEASE = Duration.ofMinutes(2);
	private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
			"if redis.call('get', KEYS[1]) == ARGV[1] then "
					+ "return redis.call('del', KEYS[1]) else return 0 end",
			Long.class
	);

	private final StringRedisTemplate redisTemplate;

	public Optional<Lease> tryAcquire() {
		String token = UUID.randomUUID().toString();
		try {
			Boolean acquired = redisTemplate.opsForValue().setIfAbsent(KEY, token, LEASE);
			return Boolean.TRUE.equals(acquired)
					? Optional.of(new Lease(token))
					: Optional.empty();
		} catch (RuntimeException exception) {
			log.warn("Table-group expiry lock unavailable; cleanup skipped. exceptionType={}",
					exception.getClass().getSimpleName());
			return Optional.empty();
		}
	}

	public void release(Lease lease) {
		if (lease == null) {
			return;
		}
		try {
			redisTemplate.execute(RELEASE_IF_OWNER, List.of(KEY), lease.token());
		} catch (RuntimeException exception) {
			log.warn("Table-group expiry lock release failed; lease will expire. exceptionType={}",
					exception.getClass().getSimpleName());
		}
	}

	public record Lease(String token) {
	}
}
