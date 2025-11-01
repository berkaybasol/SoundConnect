package com.berkayb.soundconnect.shared.realtime;

import java.util.UUID;

/**
 * WebSocket/STOMP destinasyon sabitleri.
 * Moduller buradan referans alir.
 */
public class WebSocketChannels {
	
	private WebSocketChannels() {}
	
	
	// Notification Channels
	public static final String TOPIC_NOTIFICATIONS = "/topic/notifications";
	
	/**
	 * Kullaniciya ozel bildirim kanali
	 * Client subscribe: /topic/notifications/{userId}
	 */
	public static String notifications (UUID userId) {
		return TOPIC_NOTIFICATIONS + "/" + userId;
	}
	
	// kullaniciya ozel unread badgge kanali
	public static String notificationsBadge(UUID userId) {
		return TOPIC_NOTIFICATIONS + "/" + userId + "/badge";
	}
	
	// DM CHANNELS
	public static final String TOPIC_DM = "/topic/dm";
	
	// kullanicinin dm mesajlari icin kanal
	public static String dm(UUID userId) { return TOPIC_DM + "/" + userId; }
	
	// kullanicinin dm unread badge kanali
	public static String dmBadge(UUID userId) {
		return TOPIC_DM + "/" + userId + "/badge";
	}
	
	// TableGroup Chat Channels
	public static final String TOPIC_TABLE_GROUP = "/topic/table_group";
	
	// Masa icin ortak grup soihbet kanali
	public static String tableGroup(UUID tableGroupId) {
		return TOPIC_TABLE_GROUP + "/" + tableGroupId;
	}
	
}