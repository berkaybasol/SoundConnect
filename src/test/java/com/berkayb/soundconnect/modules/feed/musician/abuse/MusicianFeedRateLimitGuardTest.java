package com.berkayb.soundconnect.modules.feed.musician.abuse;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.ServiceUnavailableRetryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class MusicianFeedRateLimitGuardTest {
    private StringRedisTemplate redis;
    private MusicianFeedRateLimitProperties properties;
    private MusicianFeedRateLimitGuard guard;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        properties = new MusicianFeedRateLimitProperties();
        properties.setEnabled(true);
        guard = new MusicianFeedRateLimitGuard(redis, properties);
    }

    @Test
    void initialPageAtomicallyUsesInitialAndSharedBucketsInOneClusterSlot() {
        UUID userId = UUID.randomUUID();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        assertThatCode(() -> guard.checkPage(userId, false)).doesNotThrowAnyException();

        verify(redis).execute(any(RedisScript.class), eq(List.of(
                        "soundconnect:musician-feed:rate-limit:{" + userId + "}:initial",
                        "soundconnect:musician-feed:rate-limit:{" + userId + "}:pages")),
                eq("4"), eq("15000"), eq("120000"),
                eq("30"), eq("3000"), eq("180000"));
    }

    @Test
    void continuationUsesItsOwnBurstAndTheSameSharedPageBudget() {
        UUID userId = UUID.randomUUID();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        guard.checkPage(userId, true);

        verify(redis).execute(any(RedisScript.class), eq(List.of(
                        "soundconnect:musician-feed:rate-limit:{" + userId + "}:continuation",
                        "soundconnect:musician-feed:rate-limit:{" + userId + "}:pages")),
                eq("12"), eq("2000"), eq("48000"),
                eq("30"), eq("3000"), eq("180000"));
    }

    @Test
    void telemetryUsesAnIndependentBoundedBucket() {
        UUID userId = UUID.randomUUID();
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        guard.checkTelemetry(userId);

        verify(redis).execute(any(RedisScript.class), eq(List.of(
                        "soundconnect:musician-feed:rate-limit:{" + userId + "}:telemetry")),
                eq("120"), eq("250"), eq("60000"));
    }

    @Test
    void rejectionUsesFeedSpecific429AndRoundsRetryAfterUp() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1_001L);

        assertThatThrownBy(() -> guard.checkPage(UUID.randomUUID(), true))
                .isInstanceOfSatisfying(RateLimitedException.class, exception -> {
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.MUSICIAN_FEED_RATE_LIMITED);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(2L);
                });
    }

    @Test
    void redisFailureAndInvalidScriptResultsFailClosedWithRetryAdvice() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("offline"));

        assertUnavailable(() -> guard.checkPage(UUID.randomUUID(), false));

        redis = mock(StringRedisTemplate.class);
        properties.setUnavailableRetryAfter(Duration.ofMillis(5_001));
        guard = new MusicianFeedRateLimitGuard(redis, properties);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(null);
        assertThatThrownBy(() -> guard.checkTelemetry(UUID.randomUUID()))
                .isInstanceOfSatisfying(ServiceUnavailableRetryException.class, exception -> {
                    assertThat(exception.getErrorType())
                            .isEqualTo(ErrorType.MUSICIAN_FEED_RATE_LIMIT_UNAVAILABLE);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(6L);
                });
    }

    @Test
    void disabledLocalProtectionDoesNotTouchRedisButStillRequiresIdentity() {
        properties.setEnabled(false);

        guard.checkPage(UUID.randomUUID(), false);
        guard.checkTelemetry(UUID.randomUUID());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
        assertThatThrownBy(() -> guard.checkPage(null, false)).isInstanceOf(NullPointerException.class);
    }

    private void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ServiceUnavailableRetryException.class, exception -> {
                    assertThat(exception.getErrorType())
                            .isEqualTo(ErrorType.MUSICIAN_FEED_RATE_LIMIT_UNAVAILABLE);
                    assertThat(exception.getRetryAfterSeconds()).isEqualTo(5L);
                });
    }
}
