package com.berkayb.soundconnect.modules.collab.ttl.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollabTTLServiceImpl implements CollabTTLService {
	
	private final RedisTemplate<String, String> redisTemplate;
	
	private static final String KEY_PREFIX = "collab:expire:";
	
	@Override
	public void setTTL(UUID collabId, LocalDateTime expirationTime) {
		if (expirationTime == null) {
			log.debug("[CollabTTL] no TTL set because expirationTime is null");
			return;
		}
		
		Duration ttl = Duration.between(LocalDateTime.now(), expirationTime);
		
		if (ttl.isNegative() || ttl.isZero()) {
			log.warn("[CollabTTL] Expiration time already passed for {}. No TTL written", collabId);
			return;
		}
		String key = KEY_PREFIX + collabId;
		redisTemplate.opsForValue().set(key, "1", ttl);
		
		log.info("[CollabTTL] TTL set for collab {} (expires in {} seconds", collabId, ttl.getSeconds());
	}
	
	@Override
	public void resetTTL(UUID collabId, LocalDateTime newExpiration) {
		deleteTTL(collabId);
		setTTL(collabId, newExpiration);
		
		log.info("[CollabTTL] TTL reset for collab {}", collabId);
	}
	
	@Override
	public void deleteTTL(UUID collabId) {
		String key = KEY_PREFIX + collabId;
		Boolean result = redisTemplate.delete(key);
		
		if (result != null && result) {
			log.info("[CollabTTL] TTL key removed for collab {}", collabId);
		}
	}
}