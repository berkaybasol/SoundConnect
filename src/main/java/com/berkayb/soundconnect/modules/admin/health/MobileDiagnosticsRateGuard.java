package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimiter;
import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimitProperties;
import com.berkayb.soundconnect.auth.ratelimit.TrustedProxyClientAddressResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

/** Reuses the existing atomic, hashed-identity limiter; diagnostics cannot disable that guard. */
@Component
public class MobileDiagnosticsRateGuard {
    private final AuthRateLimiter limiter;
    private final TrustedProxyClientAddressResolver addresses;
    private final MobileDiagnosticsProperties properties;

    public MobileDiagnosticsRateGuard(StringRedisTemplate redis, TrustedProxyClientAddressResolver addresses,
                                      MobileDiagnosticsProperties properties) {
        var limits = new AuthRateLimitProperties();
        limits.setKeyPrefix("soundconnect:mobile-diagnostics");
        this.limiter = new AuthRateLimiter(redis, limits);
        this.addresses = addresses; this.properties = properties;
    }

    AuthRateLimiter.Decision check(UUID account, HttpServletRequest request) {
        var global = limiter.checkAccount("ingress", "global", policy(properties.getGlobalPerMinute()));
        if (!global.allowed()) return global;
        var ip = limiter.check("ingress", addresses.resolve(request), policy(properties.getPerIpPerMinute()));
        if (!ip.allowed()) return ip;
        return limiter.checkAccount("events", account.toString(), policy(properties.getPerAccountPerMinute()));
    }

    private static AuthRateLimitProperties.Policy policy(int count) {
        return new AuthRateLimitProperties.Policy(count, Duration.ofMinutes(1));
    }
}
