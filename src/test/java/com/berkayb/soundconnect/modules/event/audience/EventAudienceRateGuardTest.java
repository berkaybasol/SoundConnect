package com.berkayb.soundconnect.modules.event.audience;

import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventAudienceRateGuardTest {
    @Test void accountScopedQuotaAllowsZeroAndMapsLimitWithRetryHeaderContract() {
        var redis=mock(StringRedisTemplate.class); var guard=new EventAudienceRateGuard(redis); UUID actor=UUID.randomUUID();
        when(redis.execute(any(),eq(List.of("soundconnect:event-intent:user:"+actor)))).thenReturn(0L,19L);
        guard.check(actor);
        assertThat(catchThrowableOfType(() -> guard.check(actor),RateLimitedException.class).getErrorType()).isEqualTo(ErrorType.EVENT_INTENT_RATE_LIMITED);
    }
    @Test void redisFailureAndMissingResultFailClosed() {
        var redis=mock(StringRedisTemplate.class); var guard=new EventAudienceRateGuard(redis);
        assertThat(catchThrowableOfType(() -> guard.check(UUID.randomUUID()),ServiceUnavailableRetryException.class).getErrorType()).isEqualTo(ErrorType.EVENT_INTENT_UNAVAILABLE);
        when(redis.execute(any(),anyList())).thenThrow(new IllegalStateException("Redis offline"));
        assertThatThrownBy(() -> guard.check(UUID.randomUUID())).isInstanceOf(ServiceUnavailableRetryException.class);
    }
}
