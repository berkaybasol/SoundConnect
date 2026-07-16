package com.berkayb.soundconnect.modules.media.abuse;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.RateLimitedException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaUploadAbuseGuard {

	@SuppressWarnings("rawtypes")
	private static final DefaultRedisScript<List> RESERVE = new DefaultRedisScript<>("""
			local now = tonumber(ARGV[1])
			local window = tonumber(ARGV[2])
			local requestLimit = tonumber(ARGV[3])
			local byteLimit = tonumber(ARGV[4])
			local concurrentLimit = tonumber(ARGV[5])
			local reservation = ARGV[6]
			local reservationTtl = tonumber(ARGV[7])

			redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
			local active = redis.call('ZCARD', KEYS[3])
			if active >= concurrentLimit then
			  local earliest = redis.call('ZRANGE', KEYS[3], 0, 0, 'WITHSCORES')
			  local retry = reservationTtl
			  if earliest[2] then retry = math.max(1000, tonumber(earliest[2]) - now) end
			  return {3, retry}
			end

			local requests = tonumber(redis.call('GET', KEYS[1]) or '0')
			local bytes = tonumber(redis.call('GET', KEYS[2]) or '0')
			if requests + 1 > requestLimit then
			  local retry = redis.call('PTTL', KEYS[1])
			  if retry < 1 then retry = window end
			  return {1, retry}
			end
			if bytes + tonumber(ARGV[8]) > byteLimit then
			  local retry = redis.call('PTTL', KEYS[2])
			  if retry < 1 then retry = window end
			  return {2, retry}
			end

			local newRequests = redis.call('INCR', KEYS[1])
			if newRequests == 1 then redis.call('PEXPIRE', KEYS[1], window) end
			local newBytes = redis.call('INCRBY', KEYS[2], ARGV[8])
			if newBytes == tonumber(ARGV[8]) then redis.call('PEXPIRE', KEYS[2], window) end
			redis.call('ZADD', KEYS[3], now + reservationTtl, reservation)
			redis.call('PEXPIRE', KEYS[3], reservationTtl + 60000)
			return {0, 0}
			""", List.class);

	private static final long WARNING_INTERVAL_MILLIS = 60_000L;

	private final StringRedisTemplate redisTemplate;
	private final MediaUploadGuardProperties properties;
	private final AtomicLong lastRedisWarningAt = new AtomicLong(0L);

	public void reserve(UUID actingUserId, UUID assetId, long sizeBytes) {
		if (actingUserId == null || assetId == null || sizeBytes <= 0) {
			throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_INVALID_REQUEST);
		}
		if (!properties.isEnabled()) return;

		String userTag = "{" + actingUserId + "}";
		String baseKey = properties.getKeyPrefix() + ":" + userTag;
		long now = System.currentTimeMillis();
		long windowMillis = properties.getWindow().toMillis();
		long reservationMillis = properties.getReservationTtl().toMillis();

		try {
			List<?> result = redisTemplate.execute(
					RESERVE,
					List.of(baseKey + ":requests", baseKey + ":bytes", baseKey + ":active"),
					Long.toString(now),
					Long.toString(windowMillis),
					Integer.toString(properties.getMaxRequestsPerWindow()),
					Long.toString(properties.getMaxBytesPerWindow()),
					Integer.toString(properties.getMaxConcurrentUploads()),
					assetId.toString(),
					Long.toString(reservationMillis),
					Long.toString(sizeBytes)
			);
			if (result == null || result.isEmpty()) {
				throw new IllegalStateException("Media upload guard returned no decision");
			}
			long decision = ((Number) result.get(0)).longValue();
			long retryAfterSeconds = retryAfterSeconds(result, properties.getWindow());
			if (decision == 1 || decision == 2) {
				throw new RateLimitedException(ErrorType.MEDIA_UPLOAD_RATE_LIMITED, retryAfterSeconds);
			}
			if (decision == 3) {
				throw new RateLimitedException(ErrorType.MEDIA_UPLOAD_CONCURRENCY_LIMITED, retryAfterSeconds);
			}
			if (decision != 0) {
				throw new IllegalStateException("Media upload guard returned an unknown decision");
			}

			redisTemplate.opsForValue().set(
					reservationKey(assetId),
					actingUserId.toString(),
					properties.getReservationTtl()
			);
		} catch (SoundConnectException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			rollbackActiveSlot(actingUserId, assetId);
			warnRedisFailureOncePerInterval(exception);
			if (!properties.isFailOpen()) {
				throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_GUARD_UNAVAILABLE);
			}
		}
	}

	public void release(UUID assetId) {
		if (!properties.isEnabled() || assetId == null) return;
		try {
			String mappingKey = reservationKey(assetId);
			String userId = redisTemplate.opsForValue().get(mappingKey);
			if (userId != null && !userId.isBlank()) {
				try {
					String normalizedUserId = UUID.fromString(userId).toString();
					String activeKey = properties.getKeyPrefix() + ":{" + normalizedUserId + "}:active";
					redisTemplate.opsForZSet().remove(activeKey, assetId.toString());
				} catch (IllegalArgumentException invalidMapping) {
					log.warn("Media upload reservation mapping was invalid and has been discarded");
				}
			}
			redisTemplate.delete(mappingKey);
		} catch (RuntimeException exception) {
			warnRedisFailureOncePerInterval(exception);
		}
	}

	public void releaseAfterCommit(UUID assetId) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					release(assetId);
				}
			});
		} else {
			release(assetId);
		}
	}

	public void releaseAfterRollback(UUID assetId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
					release(assetId);
				}
			}
		});
	}

	private String reservationKey(UUID assetId) {
		return properties.getKeyPrefix() + ":reservation:" + assetId;
	}

	private static long retryAfterSeconds(List<?> result, Duration fallback) {
		long retryMillis = result.size() > 1 && result.get(1) instanceof Number number
				? number.longValue()
				: fallback.toMillis();
		return Math.max(1L, (Math.max(1L, retryMillis) + 999L) / 1_000L);
	}

	private void rollbackActiveSlot(UUID actingUserId, UUID assetId) {
		try {
			String activeKey = properties.getKeyPrefix() + ":{" + actingUserId + "}:active";
			redisTemplate.opsForZSet().remove(activeKey, assetId.toString());
			redisTemplate.delete(reservationKey(assetId));
		} catch (RuntimeException rollbackFailure) {
			warnRedisFailureOncePerInterval(rollbackFailure);
		}
	}

	private void warnRedisFailureOncePerInterval(RuntimeException exception) {
		long now = System.currentTimeMillis();
		long previous = lastRedisWarningAt.get();
		if (now - previous >= WARNING_INTERVAL_MILLIS
				&& lastRedisWarningAt.compareAndSet(previous, now)) {
			log.warn("Media upload abuse guard unavailable. failOpen={} exceptionType={}",
					properties.isFailOpen(), exception.getClass().getSimpleName());
		}
	}
}
