package com.berkayb.soundconnect.modules.comment.abuse;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.shared.exception.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/** Short same-content burst protection, not an account-wide penalty or a comment idempotency mechanism. */
@Component
@RequiredArgsConstructor
@Slf4j
public class CommentBurstGuard {
    static final int LIMIT = 3;
    static final long WINDOW_MILLIS = 30_000;
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local window = tonumber(ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
                local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
                return math.max(1, math.ceil((tonumber(oldest[2]) + window - now) / 1000))
            end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final AtomicLong lastWarning = new AtomicLong();

    /** Call only after validation, in the transaction that will create the comment/reply. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID userId, EngagementTargetType targetType, UUID targetId) {
        if (userId == null || targetType == null || targetId == null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new ServiceUnavailableRetryException(ErrorType.COMMENT_BURST_UNAVAILABLE, 5);
        }
        String key = key(userId, targetType, targetId);
        String token = UUID.randomUUID().toString();
        AtomicBoolean cleanupRequired = new AtomicBoolean(true);
        // Register before Redis: a connection failure can hide a successfully executed reservation.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                // Unknown outcome may have committed. Never release on response serialization errors after commit.
                if (status == STATUS_ROLLED_BACK && cleanupRequired.get()) releaseRolledBackToken(key, token);
            }
        });
        try {
            Long retryAfter = redis.execute(RESERVE, List.of(key), Long.toString(WINDOW_MILLIS),
                    Integer.toString(LIMIT), token);
            if (retryAfter == null || retryAfter < 0 || retryAfter > 30) {
                throw new IllegalStateException("Invalid comment burst reservation result");
            }
            if (retryAfter > 0) {
                cleanupRequired.set(false); // Confirmed rejection inserted no member; avoid a second Redis command.
                throw new RateLimitedException(ErrorType.COMMENT_BURST_RATE_LIMITED, retryAfter);
            }
        } catch (RateLimitedException limited) {
            throw limited;
        } catch (RuntimeException unavailable) {
            warnOnce(unavailable);
            throw new ServiceUnavailableRetryException(ErrorType.COMMENT_BURST_UNAVAILABLE, 5);
        }
    }

    static String key(UUID userId, EngagementTargetType type, UUID targetId) {
        return "soundconnect:comments:burst:" + userId + ":" + type.name() + ":" + targetId;
    }

    private void releaseRolledBackToken(String key, String token) {
        try { redis.opsForZSet().remove(key, token); }
        catch (RuntimeException unavailable) {
            // Best effort only; the original reservation expires within 30s even if Redis remains unavailable.
            warnOnce(unavailable);
        }
    }

    private void warnOnce(RuntimeException failure) {
        long now = System.currentTimeMillis(), previous = lastWarning.get();
        if (now - previous >= 60_000 && lastWarning.compareAndSet(previous, now)) {
            log.warn("Comment burst guard unavailable; sends fail closed. exceptionType={}", failure.getClass().getSimpleName());
        }
    }
}
