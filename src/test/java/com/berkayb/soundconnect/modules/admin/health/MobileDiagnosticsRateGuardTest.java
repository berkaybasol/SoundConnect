package com.berkayb.soundconnect.modules.admin.health;

import com.berkayb.soundconnect.auth.ratelimit.AuthRateLimiter;
import com.berkayb.soundconnect.auth.ratelimit.TrustedProxyClientAddressResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MobileDiagnosticsRateGuardTest {
    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void usesExistingHashedIpAccountAndGlobalLimiterWithoutTrustingCallerHeaders() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(List.of(1L, 60L));
        var guard = new MobileDiagnosticsRateGuard(redis, new TrustedProxyClientAddressResolver(List.of()), new MobileDiagnosticsProperties());
        var http = new MockHttpServletRequest(); http.setRemoteAddr("192.0.2.20"); http.addHeader("X-Forwarded-For", "203.0.113.10");
        UUID actor = UUID.randomUUID();
        assertThat(guard.check(actor, http).allowed()).isTrue();
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(redis, times(3)).execute(any(RedisScript.class), keys.capture(), anyString());
        assertThat(keys.getAllValues().toString()).doesNotContain(actor.toString(), "192.0.2.20", "203.0.113.10");
        assertThat(keys.getAllValues().stream().map(list -> list.getFirst().toString())).allMatch(key -> key.matches("soundconnect:mobile-diagnostics:[a-z]+:(?:account|ip):[a-f0-9]{64}"));
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void redisFailureInvalidReplyAndGlobalQuotaFailClosedBeforeFurtherWork() {
        var redis = mock(StringRedisTemplate.class);
        var guard = new MobileDiagnosticsRateGuard(redis, new TrustedProxyClientAddressResolver(List.of()), new MobileDiagnosticsProperties());
        var http = new MockHttpServletRequest(); http.setRemoteAddr("192.0.2.1");
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(null);
        assertThat(guard.check(UUID.randomUUID(), http).status()).isEqualTo(AuthRateLimiter.Status.UNAVAILABLE);
        verify(redis).execute(any(RedisScript.class), anyList(), anyString());
        reset(redis);
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(List.of(201L, 48L));
        var limit = guard.check(UUID.randomUUID(), http);
        assertThat(limit.status()).isEqualTo(AuthRateLimiter.Status.LIMITED);
        assertThat(limit.retryAfterSeconds()).isEqualTo(48);
        verify(redis).execute(any(RedisScript.class), anyList(), anyString());
    }
}
