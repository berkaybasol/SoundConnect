package com.berkayb.soundconnect.modules.notification.websocket;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationWebSocketServiceImplTest {
	
	@Mock
	org.springframework.messaging.simp.SimpMessagingTemplate template;
	
	@Test
	void destinations_are_correct() {
		var svc = new NotificationWebSocketServiceImpl(template);
		var user = UUID.randomUUID();
		var dto = new com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto(
				UUID.randomUUID(), user, NotificationType.SOCIAL_NEW_FOLLOWER, "t", "m", false, null, Map.of()
		);
		
		svc.sendNotificationToUser(user, dto);
		verify(template).convertAndSend(
				eq(com.berkayb.soundconnect.shared.realtime.WebSocketChannels.notifications(user)),
				eq(dto)
		);
		
		reset(template);
		
		svc.sendUnreadBadgeToUser(user, 7L);
		verify(template).convertAndSend(
				eq(com.berkayb.soundconnect.shared.realtime.WebSocketChannels.notificationsBadge(user)),
				eq(7L)
		);
	}
}