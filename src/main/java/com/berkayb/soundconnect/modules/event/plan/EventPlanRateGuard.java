package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Shared account quotas bound fan-out writes without coupling scheduler progress to Redis. */
@Component
@RequiredArgsConstructor
public class EventPlanRateGuard {
    private static final DefaultRedisScript<Long> QUOTA = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= tonumber(ARGV[1]) then
                return math.max(1, redis.call('TTL', KEYS[1]))
            end
            count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 60) end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public void check(UUID actor) { consume(actor, "write", 30); }

    public void checkPreview(UUID actor) { consume(actor, "preview", 60); }

    private void consume(UUID actor, String operation, int limit) {
        if (actor == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        try {
            Long retry = redis.execute(QUOTA,
                    List.of("soundconnect:event-plan:" + operation + ":" + actor), Integer.toString(limit));
            if (retry == null || retry < 0) throw new IllegalStateException("Invalid quota response");
            if (retry > 0) throw new RateLimitedException(ErrorType.EVENT_PLAN_RATE_LIMITED, retry);
        } catch (RateLimitedException limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            throw new ServiceUnavailableRetryException(ErrorType.EVENT_PLAN_UNAVAILABLE, 5);
        }
    }
}
