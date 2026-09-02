package com.berkayb.soundconnect.shared.realtime;

import java.util.UUID;

/**
 * Canonical WebSocket/STOMP broker destinations.
 *
 * <p>RabbitMQ maps {@code /topic/<name>} to an AMQP topic routing key. Raw
 * slashes inside {@code <name>} are therefore not valid Rabbit STOMP topic
 * destinations. Routing-key segments below are deliberately separated with
 * dots so the same contract works with both Spring's simple broker and the
 * production RabbitMQ broker relay.</p>
 */
public final class WebSocketChannels {
	
	private WebSocketChannels() {}
	
	
	// Notification Channels
	public static final String TOPIC_NOTIFICATIONS = "/topic/notifications";
	
	/** Client subscribe: {@code /topic/notifications.<userId>} */
	public static String notifications (UUID userId) {
		return TOPIC_NOTIFICATIONS + "." + userId;
	}
	
	// Kullaniciya ozel unread badge kanali.
	public static String notificationsBadge(UUID userId) {
		return TOPIC_NOTIFICATIONS + "." + userId + ".badge";
	}
	
	// DM CHANNELS
	public static final String TOPIC_DM = "/topic/dm";
	
	// Kullanicinin DM mesajlari icin kanal.
	public static String dm(UUID userId) { return TOPIC_DM + "." + userId; }
	
	// Kullanicinin DM unread badge kanali.
	public static String dmBadge(UUID userId) {
		return TOPIC_DM + "." + userId + ".badge";
	}
	
	// TableGroup Chat Channels
	public static final String TOPIC_TABLE_GROUP = "/topic/table-group";
	
	// Masa icin ortak grup sohbet kanali.
	public static String tableGroup(UUID tableGroupId) {
		return TOPIC_TABLE_GROUP + "." + tableGroupId;
	}
	
	// Pulse kanali
	public static final String TOPIC_PULSE = "/topic/pulse";
	
	public static String pulseRoom(UUID roomId) {
		return TOPIC_PULSE + "." + roomId;
	}
	
	// Pulse Voting kanali
	public static String pulseVote(UUID roomId) {
		return pulseRoom(roomId) + ".vote";
	}

	public static String pulseState(UUID roomId) {
		return pulseRoom(roomId) + ".state";
	}

	public static String pulsePresence(UUID roomId) {
		return pulseRoom(roomId) + ".presence";
	}
}
