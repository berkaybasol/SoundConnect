package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.shared.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;

/** Uses the existing shared Redis connection; account-scoped 60 writes/minute, fail closed. */
@Component @RequiredArgsConstructor
public class EventAudienceRateGuard {
    private static final DefaultRedisScript<Long> QUOTA = new DefaultRedisScript<>("""
            local count = tonumber(redis.call('GET', KEYS[1]) or '0')
            if count >= 60 then return math.max(1, redis.call('TTL', KEYS[1])) end
            count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 60) end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    public void check(UUID userId) {
        try {
            Long retry = redis.execute(QUOTA, List.of("soundconnect:event-intent:user:" + userId));
            if (retry == null) throw new IllegalStateException("Missing quota result");
            if (retry > 0) throw new RateLimitedException(ErrorType.EVENT_INTENT_RATE_LIMITED, retry);
        } catch (RateLimitedException limited) { throw limited; }
        catch (RuntimeException unavailable) { throw new ServiceUnavailableRetryException(ErrorType.EVENT_INTENT_UNAVAILABLE, 5); }
    }
}
