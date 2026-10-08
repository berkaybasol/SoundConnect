package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Best-effort projections only after a successful moderation commit. REST remains authoritative. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmModerationProjection {
    private final DMMessageRepository messages;
    private final NotificationService notifications;
    private final NotificationWebSocketService websocket;
    private final SimpMessagingTemplate messaging;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void refresh(DmModeratedEvent event) {
        for (var participant : event.participants()) {
            try {
                messaging.convertAndSend(WebSocketChannels.dmBadge(participant),
                        messages.countByRecipientIdAndReadAtIsNull(participant));
                websocket.sendUnreadBadgeToUser(participant, notifications.getUnreadCount(participant));
            } catch (RuntimeException failure) {
                log.warn("DM moderation badge projection failed exceptionType={}", failure.getClass().getSimpleName());
            }
        }
    }
}
