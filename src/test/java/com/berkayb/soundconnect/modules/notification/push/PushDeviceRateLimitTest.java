package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PushDeviceRateLimitTest {
    @Test void sharedAccountBucketAllowsWithinLimitAndHonorsServerRetryAfter() {
        var redis=mock(StringRedisTemplate.class); var properties=new PushProperties();
        var limiter=new PushDeviceRateLimit(redis,properties);
        when(redis.execute(eq(PushDeviceRateLimit.LIMIT),anyList(),anyString())).thenReturn(0L,17L);
        var user=UUID.randomUUID(); limiter.check(user);
        assertThatThrownBy(()->limiter.check(user)).isInstanceOfSatisfying(RateLimitedException.class,
                failure->assertThat(failure.getRetryAfterSeconds()).isEqualTo(17));
        verify(redis,times(2)).execute(eq(PushDeviceRateLimit.LIMIT),eq(java.util.List.of("soundconnect:push-device:mutation:"+user)),eq("60"));
    }
    @Test void absentOrFailedRedisResponseFailsClosedWithSafeRetry() {
        var redis=mock(StringRedisTemplate.class); var limiter=new PushDeviceRateLimit(redis,new PushProperties());
        when(redis.execute(eq(PushDeviceRateLimit.LIMIT),anyList(),anyString())).thenReturn(null).thenThrow(new IllegalStateException("private host"));
        for(int i=0;i<2;i++) assertThatThrownBy(()->limiter.check(UUID.randomUUID()))
                .isInstanceOfSatisfying(ServiceUnavailableRetryException.class,failure->{
                    assertThat(failure.getRetryAfterSeconds()).isEqualTo(5); assertThat(failure.getCause()).isNull();
                });
    }
}
