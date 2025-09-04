package com.berkayb.soundconnect.shared.realtime;

import java.util.UUID;

/**
 * WebSocket/STOMP destinasyon sabitleri.
 * Moduller buradan referans alir.
 */
public class WebSocketChannels {
	
	private WebSocketChannels() {}
	
	public static final String TOPIC_NOTIFICATIONS = "/topic/notifications";
	//TODO public static final String TOPIC_DM = "/topic/dm";
	
	// Kullaniciya ozel kanal insasi
	public static String notifications (UUID userId) {
		return TOPIC_NOTIFICATIONS + "/" + userId;
	}
	
	public static String notificationsBadge(UUID userId) {
		return TOPIC_NOTIFICATIONS + "/" + userId + "/badge";
	}
	
	// TODO DM Module
}