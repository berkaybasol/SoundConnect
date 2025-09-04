package com.berkayb.soundconnect.modules.notification.helper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

import static java.lang.Math.max;

/**
 * Notification modulunde unread badge sayisini Redis cache uzerinden yoneten yardimci sinif.
 * Baska modullerde benzer badge cache ihtiyaci dogarsa bu sinif yeniden kullanilabilir.
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationBadgeCacheHelper {
	
	// Redis key sablonlari
	private static final String UNREAD_COUNT_KEY_FMT = "unread_count:%s"; // kullaniciya ozel badge sayisi icin. %s → recipient UUID
	private static final Duration UNREAD_COUNT_TTL = Duration.ofMinutes(15); // badge sayaci 15dk cache
	
	private final StringRedisTemplate stringRedisTemplate;
	
	// kullaniciya ozel Redis key uretir.
	public String unreadKey(UUID userId) {
		return String.format(UNREAD_COUNT_KEY_FMT, userId);
	}
	
	// Cache'deki unread sayacini n kadar azaltir. eger cache yoksa freshUnreadSupplier ile gercek sayi hesaplanir.
	// sayinin negatif olmamasi icin Math.max(0,...) kullanilir.
	public void decrementUnreadSafely(UUID userId, int n, long freshUnread) {
		String key = unreadKey(userId);
		try {
			String val = stringRedisTemplate.opsForValue().get(key);
			if (val == null) {
				// cache yoksa freshUnread ile gercek unread sayiyi paramatre olarak al
				long decreased = max(0, freshUnread - n);
				stringRedisTemplate.opsForValue().set(key, Long.toString(decreased), UNREAD_COUNT_TTL);
				return;
			}
			long current = Long.parseLong(val);
			long next = max(0, current - n);
			stringRedisTemplate.opsForValue().set(key, Long.toString(next), UNREAD_COUNT_TTL);
		} catch (Exception e) {
			log.warn("UNREAD count cache decrement failed: key={}, err={}", key, e.toString());
		}
	}
	
	// Cache'deki unread sayacini dogrudan verilen degere ayarlar. genellikle sifirlamak icin kullanilir (negatif girilse bile 0'a sabit)
	public void setUnread(UUID userId, long value){
		String key = unreadKey(userId);
		try {
			stringRedisTemplate.opsForValue().set(key, Long.toString(max(0, value)), UNREAD_COUNT_TTL);
		} catch (Exception e) {
			log.warn("UNREAD count cache set failed: key={}, err={}", key, e.toString());
		}
	}
	
	// Cache'deki unread sayacini okur eger cache'de veri yoksa null doner
	public Long getCacheUnread(UUID userId) {
		String key = unreadKey(userId);
		try {
			String cached = stringRedisTemplate.opsForValue().get(key);
			if (cached != null) {
				return Long.parseLong(cached);
			}
		} catch (Exception e) {
			log.warn("UNREAD count cache read failed: key={}, err={}", key, e.toString());
		}
		return null;
	}
	
	// TTL ile birlikte unread sayacini ayarlamak icin yardimci method ( setUnread zaten TTL iceriyo)
	public void setUnreadWithTtl(UUID userId, long count) {
		setUnread(userId, count);
	}
	
	// TTL getter
	public static Duration getUnreadCountTtl(){
		return UNREAD_COUNT_TTL;
	}
}