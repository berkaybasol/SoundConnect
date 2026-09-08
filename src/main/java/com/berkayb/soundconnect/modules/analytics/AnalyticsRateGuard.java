package com.berkayb.soundconnect.modules.analytics;

import com.berkayb.soundconnect.auth.ratelimit.TrustedProxyClientAddressResolver;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.*;

@Component @RequiredArgsConstructor
public class AnalyticsRateGuard {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local cost=tonumber(ARGV[1]); local retry=0
            for i=1,#KEYS do
              if tonumber(redis.call('GET',KEYS[i]) or '0')+cost>tonumber(ARGV[i+1]) then
                local ttl=redis.call('TTL',KEYS[i]); if ttl<1 then ttl=60 end
                if ttl>retry then retry=ttl end
              end
            end
            if retry>0 then return retry end
            for i=1,#KEYS do
              local count=redis.call('INCRBY',KEYS[i],cost)
              if count==cost then redis.call('EXPIRE',KEYS[i],60) end
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final TrustedProxyClientAddressResolver resolver;
    private final AnalyticsIdentity identity;
    private final AnalyticsProperties properties;

    public void submission(HttpServletRequest request) {
        identity.requireEnabled();
        consume(List.of(key("submit-ip", resolver.resolve(request)), "soundconnect:{venue-analytics}:submit-global"),
                1, properties.getIpRequestsPerMinute(), properties.getGlobalRequestsPerMinute());
    }
    public void observations(UUID userId, UUID clientId, int count) {
        consume(List.of(key("actor", identity.actor(userId, clientId))), count, properties.getActorObservationsPerMinute());
    }
    public void read(HttpServletRequest request, UUID userId) {
        identity.requireEnabled();
        consume(List.of(key("read-owner", userId.toString()), key("read-ip", resolver.resolve(request)),
                        "soundconnect:{venue-analytics}:read-global"),
                1, properties.getOwnerReadsPerMinute(), properties.getIpRequestsPerMinute(), properties.getGlobalRequestsPerMinute());
    }
    private String key(String scope, String subject) { return "soundconnect:{venue-analytics}:" + scope + ":" + identity.quotaKey(scope, subject); }
    private void consume(List<String> keys, int cost, int... limits) {
        var arguments = new ArrayList<String>(); arguments.add(Integer.toString(cost));
        for (int limit : limits) arguments.add(Integer.toString(limit));
        try {
            Long result = redis.execute(SCRIPT, keys, arguments.toArray());
            if (result == null || result < 0) throw AnalyticsIdentity.unavailable();
            if (result > 0) throw new RateLimitedException(ErrorType.ANALYTICS_RATE_LIMITED, result);
        } catch (RateLimitedException limited) { throw limited; }
        catch (RuntimeException unavailable) { throw AnalyticsIdentity.unavailable(); }
    }
}
