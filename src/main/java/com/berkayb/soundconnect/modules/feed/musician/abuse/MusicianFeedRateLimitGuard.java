package com.berkayb.soundconnect.modules.feed.musician.abuse;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fail-closed, account-scoped request protection for feed reads and telemetry.
 * Redis server time and one Lua decision keep the policy consistent across API
 * nodes. Page requests reserve both their lane bucket and the shared sustained
 * page budget atomically, so rejected attempts never consume only one bucket.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MusicianFeedRateLimitGuard {
    private static final long REDIS_WARNING_INTERVAL_MILLIS = 60_000L;

    /**
     * ARGV contains capacity, refill milliseconds and TTL milliseconds for
     * each key. Returns zero when every bucket was reserved, otherwise the
     * largest wait (milliseconds) without decrementing any bucket.
     */
    private static final DefaultRedisScript<Long> RESERVE_BUCKETS = new DefaultRedisScript<>("""
            local now_parts = redis.call('TIME')
            local now_ms = tonumber(now_parts[1]) * 1000 + math.floor(tonumber(now_parts[2]) / 1000)
            local tokens = {}
            local last_refills = {}
            local retry_after_ms = 0

            for index = 1, #KEYS do
                local argument = ((index - 1) * 3) + 1
                local capacity = tonumber(ARGV[argument])
                local refill_ms = tonumber(ARGV[argument + 1])
                local state = redis.call('HMGET', KEYS[index], 'tokens', 'last_refill_ms')
                local current_tokens = tonumber(state[1])
                local last_refill_ms = tonumber(state[2])

                if current_tokens == nil or last_refill_ms == nil then
                    current_tokens = capacity
                    last_refill_ms = now_ms
                end
                current_tokens = math.min(capacity, math.max(0, current_tokens))
                if last_refill_ms > now_ms then last_refill_ms = now_ms end
                if now_ms > last_refill_ms then
                    local refill_count = math.floor((now_ms - last_refill_ms) / refill_ms)
                    if refill_count > 0 then
                        current_tokens = math.min(capacity, current_tokens + refill_count)
                        last_refill_ms = last_refill_ms + (refill_count * refill_ms)
                    end
                end

                tokens[index] = current_tokens
                last_refills[index] = last_refill_ms
                if current_tokens < 1 then
                    local wait_ms = math.max(1, refill_ms - (now_ms - last_refill_ms))
                    retry_after_ms = math.max(retry_after_ms, wait_ms)
                end
            end

            for index = 1, #KEYS do
                local argument = ((index - 1) * 3) + 1
                local next_tokens = tokens[index]
                if retry_after_ms == 0 then next_tokens = next_tokens - 1 end
                redis.call('HSET', KEYS[index],
                    'tokens', next_tokens,
                    'last_refill_ms', last_refills[index])
                redis.call('PEXPIRE', KEYS[index], tonumber(ARGV[argument + 2]))
            end
            return retry_after_ms
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final MusicianFeedRateLimitProperties properties;
    private final AtomicLong lastRedisWarningAt = new AtomicLong(0L);

    public void checkPage(UUID userId, boolean continuation) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (!properties.isEnabled()) return;

        MusicianFeedRateLimitProperties.Bucket lane = continuation
                ? properties.getContinuation() : properties.getInitial();
        String laneName = continuation ? "continuation" : "initial";
        reserve(userId,
                List.of(new ScopedBucket(laneName, lane),
                        new ScopedBucket("pages", properties.getSharedPageBudget())));
    }

    public void checkTelemetry(UUID userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (!properties.isEnabled()) return;
        reserve(userId, List.of(new ScopedBucket("telemetry", properties.getTelemetry())));
    }

    private void reserve(UUID userId, List<ScopedBucket> buckets) {
        String accountHashTag = "{" + userId + "}";
        List<String> keys = buckets.stream()
                .map(bucket -> properties.getKeyPrefix() + ":" + accountHashTag + ":" + bucket.scope())
                .toList();
        List<String> arguments = new ArrayList<>(buckets.size() * 3);
        long maxExpectedRetryMillis = 0L;
        try {
            for (ScopedBucket scoped : buckets) {
                MusicianFeedRateLimitProperties.Bucket bucket = scoped.bucket();
                long refillMillis = bucket.getRefillPeriod().toMillis();
                arguments.add(Integer.toString(bucket.getBurstCapacity()));
                arguments.add(Long.toString(refillMillis));
                arguments.add(Long.toString(properties.keyTtlMillis(bucket)));
                maxExpectedRetryMillis = Math.max(maxExpectedRetryMillis, refillMillis);
            }

            Long retryAfterMillis = redisTemplate.execute(
                    RESERVE_BUCKETS, keys, arguments.toArray());
            if (retryAfterMillis == null || retryAfterMillis < 0
                    || retryAfterMillis > maxExpectedRetryMillis) {
                throw new IllegalStateException("Invalid musician feed rate-limit result");
            }
            if (retryAfterMillis == 0) return;
            throw new RateLimitedException(ErrorType.MUSICIAN_FEED_RATE_LIMITED,
                    ceilSeconds(retryAfterMillis));
        } catch (RateLimitedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            warnRedisFailureOncePerInterval(exception);
            throw new ServiceUnavailableRetryException(
                    ErrorType.MUSICIAN_FEED_RATE_LIMIT_UNAVAILABLE,
                    ceilSeconds(properties.getUnavailableRetryAfter()));
        }
    }

    private static long ceilSeconds(Duration duration) {
        return ceilSeconds(duration.toMillis());
    }

    private static long ceilSeconds(long milliseconds) {
        return Math.max(1L, (milliseconds + 999L) / 1_000L);
    }

    private void warnRedisFailureOncePerInterval(RuntimeException exception) {
        long now = System.currentTimeMillis();
        long previous = lastRedisWarningAt.get();
        if (now - previous >= REDIS_WARNING_INTERVAL_MILLIS
                && lastRedisWarningAt.compareAndSet(previous, now)) {
            log.warn("Musician feed rate limiter unavailable; feed traffic fails closed. exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    private record ScopedBucket(String scope, MusicianFeedRateLimitProperties.Bucket bucket) {
    }
}
