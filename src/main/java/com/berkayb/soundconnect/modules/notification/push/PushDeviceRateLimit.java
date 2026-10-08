package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

/** Shared per-account ingress bound, before database connections/advisory locks. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="app.notification.push.enabled",havingValue="true")
public class PushDeviceRateLimit {
    static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('EXPIRE', KEYS[1], 60) end
            if count <= tonumber(ARGV[1]) then return 0 end
            return math.max(1, redis.call('TTL', KEYS[1]))
            """,Long.class);
    private final StringRedisTemplate redis;
    private final PushProperties properties;

    public void check(UUID userId) {
        Long retry;
        try {
            retry=redis.execute(LIMIT,List.of("soundconnect:push-device:mutation:"+userId),
                    Integer.toString(properties.getMaxDeviceMutationsPerMinute()));
        } catch(RuntimeException unavailable) {
            throw new ServiceUnavailableRetryException(ErrorType.PUSH_DEVICE_UNAVAILABLE,5);
        }
        if(retry==null) throw new ServiceUnavailableRetryException(ErrorType.PUSH_DEVICE_UNAVAILABLE,5);
        if(retry>0) throw new RateLimitedException(ErrorType.PUSH_DEVICE_RATE_LIMITED,retry);
    }
}
