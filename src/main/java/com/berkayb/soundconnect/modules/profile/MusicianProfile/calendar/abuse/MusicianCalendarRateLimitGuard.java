package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.abuse;

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

/** Short distributed token bucket using the established listener-visibility policy. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MusicianCalendarRateLimitGuard {
	private static final DefaultRedisScript<Long> TOKEN_BUCKET = new DefaultRedisScript<>("""
			local capacity = tonumber(ARGV[1])
			local refill_ms = tonumber(ARGV[2])
			local now_parts = redis.call('TIME')
			local now_ms = tonumber(now_parts[1]) * 1000 + math.floor(tonumber(now_parts[2]) / 1000)
			local state = redis.call('HMGET', KEYS[1], 'tokens', 'last_refill_ms')
			local tokens = tonumber(state[1])
			local last_refill_ms = tonumber(state[2])
			if tokens == nil or last_refill_ms == nil then tokens = capacity; last_refill_ms = now_ms end
			tokens = math.min(capacity, math.max(0, tokens))
			if last_refill_ms > now_ms then last_refill_ms = now_ms end
			if now_ms > last_refill_ms then
			    local refill_count = math.floor((now_ms - last_refill_ms) / refill_ms)
			    if refill_count > 0 then
			        tokens = math.min(capacity, tokens + refill_count)
			        last_refill_ms = last_refill_ms + refill_count * refill_ms
			    end
			end
			local retry_after_ms = 0
			if tokens >= 1 then tokens = tokens - 1
			else retry_after_ms = math.max(1, refill_ms - (now_ms - last_refill_ms)) end
			redis.call('HSET', KEYS[1], 'tokens', tokens, 'last_refill_ms', last_refill_ms)
			redis.call('PEXPIRE', KEYS[1], math.max(refill_ms, capacity * refill_ms * 2))
			return retry_after_ms
			""", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final MusicianCalendarRateLimitProperties properties;
	private final AtomicLong lastWarningAt = new AtomicLong();

	public void check(UUID userId) {
		Objects.requireNonNull(userId, "userId must not be null");
		checkKey(":user:" + userId);
	}

	/** Every founder shares the band's budget; switching accounts cannot reset it. */
	public void checkBand(UUID bandId) {
		Objects.requireNonNull(bandId, "bandId must not be null");
		checkKey(":band:" + bandId);
	}

	private void checkKey(String scope) {
		if (!properties.isEnabled()) return;
		try {
			Long retryMillis = redisTemplate.execute(TOKEN_BUCKET,
					List.of(properties.getKeyPrefix() + scope),
					Integer.toString(properties.getBurstCapacity()), Long.toString(properties.getRefillPeriod().toMillis()));
			if (retryMillis == null) throw new IllegalStateException("Missing Redis calendar rate-limit result");
			if (retryMillis > 0) throw new RateLimitedException(ErrorType.MUSICIAN_CALENDAR_RATE_LIMITED,
					Math.max(1L, (retryMillis + 999L) / 1000L));
		} catch (RateLimitedException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			long now = System.currentTimeMillis();
			long previous = lastWarningAt.get();
			if (now - previous >= 60_000L && lastWarningAt.compareAndSet(previous, now)) {
				log.warn("Musician calendar rate limiter unavailable. exceptionType={}", exception.getClass().getSimpleName());
			}
			throw new ServiceUnavailableRetryException(ErrorType.MUSICIAN_CALENDAR_RATE_LIMIT_UNAVAILABLE, 5L);
		}
	}
}
