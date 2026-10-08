package com.berkayb.soundconnect.modules.message.dm.abuse;

import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DmRateLimitGuardTest {
    @Test void redisFailureOrMissingResultFailsClosedWithRetryAfter() {
        var redis = mock(StringRedisTemplate.class);
        var guard = new DmRateLimitGuard(redis, new DmRateLimitProperties());
        assertThat(catchThrowableOfType(() -> guard.check(UUID.randomUUID(), UUID.randomUUID()),
                ServiceUnavailableRetryException.class).getRetryAfterSeconds()).isEqualTo(5);
        when(redis.execute(any(), anyList(), any(), any(), any())).thenThrow(new IllegalStateException("unavailable"));
        assertThat(catchThrowableOfType(() -> guard.check(UUID.randomUUID(), UUID.randomUUID()),
                ServiceUnavailableRetryException.class).getRetryAfterSeconds()).isEqualTo(5);
    }
    @Test void explicitlyDisabledLocalPolicyDoesNotTouchRedis() {
        var redis = mock(StringRedisTemplate.class); var properties = new DmRateLimitProperties(); properties.setEnabled(false);
        new DmRateLimitGuard(redis, properties).check(UUID.randomUUID(), UUID.randomUUID());
        verifyNoInteractions(redis);
    }
}
