package com.berkayb.soundconnect.modules.collab.ttl.listener;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import org.springframework.data.redis.connection.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Redis key expiration eventi tetiklendigi anda calisir.
 *
 * Key formati: "collab:expire:{collabId}"
 *
 * RedisKeySpaceConfig -> MessageListenerAdapter -> buradaki onMessage methodu.
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class CollabExpirationListener {
	
	private final CollabRepository collabRepository;
	
	/**
	 * Redisden expiration eventi geldiginde tetiklenen metod.
	 */
	public void onMessage(Message message)
	{
		String expiredKey = new String(message.getBody(), StandardCharsets.UTF_8);
		
		log.warn("[CollabExpirationListener] Expired Redis key detecte: {}", expiredKey);
		
		// sadece collab TTL keylerini dinle
		if (!expiredKey.startsWith("collab:expire:")) {
			return;
		}
		try {
			// key formati: collab:expire:{uuid}
			String idStr = expiredKey.substring("collab:expire:".length());
			UUID collabId = UUID.fromString(idStr);
			
			log.warn("[CollabExpirationListener] Collab expired automatically: {}", collabId);
			
			// db den sil
			handleExpiration(collabId);
		} catch (Exception e) {
			log.error("[CollabExpirationListener] Expired key parsing error! key={}, error={}", expiredKey, e.getMessage());
		}
	}
	
	private void handleExpiration(UUID collabId) {
		Collab collab = collabRepository.findById(collabId)
				.orElse(null);
		
		if (collab == null) {
			log.warn("[CollabExpirationListener] Collab not found in DB (already deleted?) id={}", collabId);
			return;
		}
		
		// Hard delete - gunluk ilan zaten tekrar kullanilamaz
		collabRepository.delete(collab);
		
		log.warn("[CollabExpirationListener] Collab deleted automatically due to TTL expiration. id={}",collabId);
	}
}