package com.berkayb.soundconnect.modules.message.dm.helper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

import static java.lang.Math.max;

/**
 * Dm icin unread badge count'u Redis cache uzerinden yoneten yardimci sinif.
 * Her user icin ayri Redis key: unread_dm_count:{userId}
 * TTL ile caache de tutar.
 * Okunmanmis mesaj sayisini hizli badge icin frontend'e gonderir.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DmBadgeCacheHelper {
	// kullaniciya ozel unread DM badge icin key sablonu:
	private static final String UNREAD_DM_KEY_FMT = "unread_dm_count:%s";
	private static final Duration UNREAD_DM_TTL = Duration.ofMinutes(15);
	
	private final StringRedisTemplate redisTemplate;
	
	// kullaniciya ozel Redis key uret
	public String unreadKey(UUID userId) {
		return String.format(UNREAD_DM_KEY_FMT, userId);
	}
	
	// Cache'deki unread sayaci n kadar azalt, yoksa freshUnread ile sifirdan hesaplat
	public void decrementUnreadSafely(UUID userId, int n, long freshUnread) {
		String key = unreadKey(userId);
		try {
			String val = redisTemplate.opsForValue().get(key);
			if (val == null) {
				long decreased = max(0, freshUnread - n);
				redisTemplate.opsForValue().set(key, Long.toString(decreased), UNREAD_DM_TTL);
				return;
			}
			long current = Long.parseLong(val);
			long next = max(0,current -n);
			redisTemplate.opsForValue().set(key, Long.toString(next), UNREAD_DM_TTL);
		} catch (Exception e) {
			log.warn("DM unread count cache decrement failed: key={}, err={}", key, e.toString());
		}
	}
	
	// cache'deki unread sayacini  dogrudan verilen degere ayarlar
	public void setUnread(UUID userId, long value) {
		String key = unreadKey(userId);
		try {
			redisTemplate.opsForValue().set(key, Long.toString(value), UNREAD_DM_TTL);
		} catch (Exception e) {
			log.warn("DM unread count cache set failed: key={}, err={}", key, e.toString());
		}
	}
	
	// cache'deki unread sayacini okur yoksa null doner
	public Long getCacheUnread (UUID userId ) {
		String key = unreadKey(userId);
		try {
			String cached = redisTemplate.opsForValue().get(key);
			if (cached != null) {
				return Long.parseLong(cached);
			}
		} catch (Exception e) {
			log.warn("DM unread count cache read failed: key={}, err={}", key, e.toString());
		}
		return null;
	}
	
	// TTL getter
	public static Duration getUnreadDmTtl() {
		return UNREAD_DM_TTL;
	}
	
}