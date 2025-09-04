package com.berkayb.soundconnect.modules.notification.websocket;


import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationWebSocketServiceImpl implements NotificationWebSocketService {
	// WebSocket uzerinden client'lara (topic veya user bazli) mesaj gondermek icin kullanilir
	private final SimpMessagingTemplate simpMessagingTemplate;
	
	
	// bildirim yollayan metod
	@Override
	public void sendNotificationToUser(UUID userId, NotificationResponseDto payload) {
		// kullanicinin notification WebSocket kanalinibelirle
		String destination = WebSocketChannels.notifications(userId);
		
		// hazirlanan kanala (destination) bildirimi pushla
		try {
			simpMessagingTemplate.convertAndSend(destination, payload);
			log.debug("WS push -> dest={}, user={}, notifId={}", destination, userId, payload.id());
		} catch (Exception e) {
			// hata olursa logla. uygulamayi kirma
			log.warn("WS push FAILED -> dest={}, user={}, err={}", destination, userId, e.toString());
		}
	}
	
	@Override
	public void sendUnreadBadgeToUser(UUID userId, long unreadCount) {
		// kullanicinin badge WebScoket kanalini belirle
		String destination = WebSocketChannels.notificationsBadge(userId);
		// hazirlanan kanala okunmamis sayisini pushla.
		try {
			simpMessagingTemplate.convertAndSend(destination, unreadCount);
			log.debug("WS badge push -> dest={}, user={}, count={}", destination, userId, unreadCount);
		} catch (Exception e) {
			// hata olursa logla. uygulamayi kirma
			log.warn("WS badge push FAILED -> dest={}, user={}, err={}", destination, userId, e.toString());
		}
	}
}