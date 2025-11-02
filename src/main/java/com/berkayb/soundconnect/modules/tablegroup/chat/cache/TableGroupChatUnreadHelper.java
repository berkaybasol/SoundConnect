package com.berkayb.soundconnect.modules.tablegroup.chat.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Her masada (tableGroupp) unread chat badge'i yonetir
 * her kullanici icin her masada ayri bir unread sayaci tutulur
 * Key Pattern: table-group:chat:unread:{userId}:{tableGroupId} -> int (unreadCount)
 */

@Component
@RequiredArgsConstructor
public class TableGroupChatUnreadHelper {
	private final StringRedisTemplate redisTemplate;
	
	// Redis key formati
	private String key(UUID userId, UUID tableGroupId) {
		return "table-group:chat:unread:" + userId + ":" + tableGroupId;
	}
	
	// yeni mesaj geldiginde unread arttir
	public void incrementUnread(UUID userId, UUID tableGroupId) {
		redisTemplate.opsForValue().increment(key(userId, tableGroupId));
	}
	
	// okundu (badge sifirliyoz)
	public void resetUnread(UUID userId, UUID tableGroupId) {
		redisTemplate.delete(key(userId, tableGroupId));
	}
	
	// anlik unread badge'i oku
	public int getUnread(UUID userId, UUID tableGroupId) {
		String value = redisTemplate.opsForValue().get(key(userId, tableGroupId));
		return value == null ? 0 : Integer.parseInt(value);
	}
}