package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.auth.ratelimit.TrustedProxyClientAddressResolver;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;

@Component @RequiredArgsConstructor
public class VenueSuggestionRateGuard {
    // Check all windows before consuming any quota. Hash tags also make the keys Redis Cluster compatible.
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local retry = 0
            for i=1,3 do
              local count = tonumber(redis.call('GET', KEYS[i]) or '0')
              if count >= tonumber(ARGV[(i-1)*2+1]) then
                local ttl = redis.call('TTL', KEYS[i])
                if ttl < 1 then ttl = tonumber(ARGV[(i-1)*2+2]) end
                if ttl > retry then retry = ttl end
              end
            end
            if retry > 0 then return retry end
            for i=1,3 do
              local count = redis.call('INCR', KEYS[i])
              if count == 1 then redis.call('EXPIRE', KEYS[i], ARGV[(i-1)*2+2]) end
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final TrustedProxyClientAddressResolver resolver;
    private final VenueSuggestionProperties properties;

    public void check(HttpServletRequest request) {
        String digest = VenueSuggestionNormalizer.hash(resolver.resolve(request));
        String prefix = "soundconnect:{venue-suggestions}:";
        try {
            Long retry = redis.execute(SCRIPT, List.of(prefix + "hour:" + digest, prefix + "day:" + digest,
                    prefix + "global-hour"), Integer.toString(properties.getHourlyLimit()), "3600",
                    Integer.toString(properties.getDailyLimit()), "86400",
                    Integer.toString(properties.getGlobalHourlyLimit()), "3600");
            if (retry == null || retry < 0) throw new IllegalStateException("Missing abuse protection result");
            if (retry > 0) throw new RateLimitedException(ErrorType.VENUE_SUGGESTION_RATE_LIMITED, retry);
        } catch (RateLimitedException limited) { throw limited; }
        catch (RuntimeException unavailable) {
            throw new ServiceUnavailableRetryException(ErrorType.VENUE_SUGGESTION_UNAVAILABLE, 30);
        }
    }
}
