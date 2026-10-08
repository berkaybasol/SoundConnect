package com.berkayb.soundconnect.modules.message.dm.abuse;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

/** Two atomic shared buckets; exact committed retries never reach this guard. */
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(DmRateLimitProperties.class)
public class DmRateLimitGuard {
    static final DefaultRedisScript<Long> LIMIT = new DefaultRedisScript<>("""
            local retry = 0
            for i = 1, 2 do
              if tonumber(redis.call('GET', KEYS[i]) or '0') >= tonumber(ARGV[i]) then
                retry = math.max(retry, math.max(1, redis.call('TTL', KEYS[i])))
              end
            end
            if retry > 0 then return retry end
            for i = 1, 2 do
              local count = redis.call('INCR', KEYS[i])
              if count == 1 then redis.call('EXPIRE', KEYS[i], ARGV[3]) end
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final DmRateLimitProperties properties;

    public void check(UUID sender, UUID recipient) {
        if (!properties.isEnabled()) return;
        // The sender hash tag keeps both keys in one Redis Cluster slot.
        String base = "soundconnect:dm:send:{" + sender + "}:";
        Long retry;
        try {
            retry = redis.execute(LIMIT, List.of(base + "all", base + "recipient:" + recipient),
                    Integer.toString(properties.getGlobalLimit()), Integer.toString(properties.getRecipientLimit()),
                    Integer.toString(properties.getWindowSeconds()));
        } catch (RuntimeException unavailable) {
            throw new ServiceUnavailableRetryException(ErrorType.DM_RATE_LIMIT_UNAVAILABLE, 5);
        }
        if (retry == null) throw new ServiceUnavailableRetryException(ErrorType.DM_RATE_LIMIT_UNAVAILABLE, 5);
        if (retry > 0) throw new RateLimitedException(ErrorType.DM_RATE_LIMITED, retry);
    }
}
