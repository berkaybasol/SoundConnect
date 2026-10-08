package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.shared.exception.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventPlanRateGuardTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final EventPlanRateGuard guard = new EventPlanRateGuard(redis);

    @Test
    void refusesUnavailableProtectionInsteadOfExecutingUnboundedFanOut() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString()))
                .thenThrow(new IllegalStateException("Redis unavailable"));
        var failure = catchThrowableOfType(() -> guard.check(UUID.randomUUID()), ServiceUnavailableRetryException.class);
        assertThat(failure.getErrorType()).isEqualTo(ErrorType.EVENT_PLAN_UNAVAILABLE);
        assertThat(failure.getRetryAfterSeconds()).isEqualTo(5);
    }

    @Test
    void preservesTheRemainingWindowWhenThrottled() {
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(27L);
        var failure = catchThrowableOfType(() -> guard.check(UUID.randomUUID()), RateLimitedException.class);
        assertThat(failure.getErrorType()).isEqualTo(ErrorType.EVENT_PLAN_RATE_LIMITED);
        assertThat(failure.getRetryAfterSeconds()).isEqualTo(27);
    }

    @Test
    void previewDoesNotSpendTheMutationQuota() {
        UUID actor = UUID.randomUUID();
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(0L);
        guard.checkPreview(actor);
        guard.check(actor);
        verify(redis).execute(any(RedisScript.class), eq(List.of("soundconnect:event-plan:preview:" + actor)), eq("60"));
        verify(redis).execute(any(RedisScript.class), eq(List.of("soundconnect:event-plan:write:" + actor)), eq("30"));
    }

    @Test
    void missingPrincipalCannotCreateAnAnonymousQuotaBucket() {
        assertThatThrownBy(() -> guard.check(null)).isInstanceOfSatisfying(SoundConnectException.class,
                failure -> assertThat(failure.getErrorType()).isEqualTo(ErrorType.UNAUTHORIZED));
        verifyNoInteractions(redis);
    }
}
