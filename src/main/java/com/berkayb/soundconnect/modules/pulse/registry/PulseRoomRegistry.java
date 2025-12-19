package com.berkayb.soundconnect.modules.pulse.registry;


import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sistemde aktif olan pulse odalarinin roomId listesini tutar
 * uygulama ayaga kalktiginda olusturulan odalar buraya eklenir
 * Scheduler lifecycle kontrolunu bu liste uzerinden yapar.
 */
@Component
@Slf4j
public class PulseRoomRegistry {
	
	// thread-safe set
	private final Set<UUID> roomIds = ConcurrentHashMap.newKeySet();
	
	// yeni bir pulse odasi kaydedilir
	public void register(UUID roomId) {
		roomIds.add(roomId);
		log.debug("[PulseRoomRegistry] New room registered. roomId={}", roomId);
	}
	
	// oda registeryden kaldirilir
	public void unregister(UUID roomId) {
		roomIds.remove(roomId);
		log.debug("[PulseRoomRegistry] Room unregistered. roomId={}", roomId);
	}
	
	public Set<UUID> getAllRoomIds() {
		return Collections.unmodifiableSet(roomIds);
	}
	
	public boolean isEmpty() {
		return roomIds.isEmpty();
	}
	
	
	
	
}