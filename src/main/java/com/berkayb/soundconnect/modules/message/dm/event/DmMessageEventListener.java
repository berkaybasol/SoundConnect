package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Dm mesaj event'lerini dinleyip, WebSocket/STOMP uzerinden anlik push yapan subscriber.
 * Committed realtime projections only; inbox persistence belongs to the send transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmMessageEventListener {

	private final SimpMessagingTemplate messagingTemplate;
	private final DMMessageMapper messageMapper;
	private final DMMessageRepository messageRepository;
	private final AccountDeliveryFence accountDeliveryFence;
	
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onDmMessageSent(DmMessageSentEvent event) {
		if (event == null || !accountDeliveryFence.canDeliver(event.getRecipientId(),
				java.util.List.of(event.getSenderId(), event.getRecipientId()))) return;
		try {
			// eventteki bilgiden DMMessage entity'sini DB'den cek (responseDto icin)
			var msg = messageRepository.findById(event.getMessageId())
					.orElse(null);
			if (msg == null || msg.getDeletedAt() != null) {
				log.warn("WS DM push: Message not found! messageId={}", event.getMessageId());
				return;
			}
			DMMessageResponseDto dto = messageMapper.toResponseDto(msg);
			
			// Recipient'e anlik mesaj push (dm kanalina)
			String recipientDestination = WebSocketChannels.dm(event.getRecipientId());
			messagingTemplate.convertAndSend(recipientDestination, dto);
			log.debug("DM mesajı WS push: senderId={}, dest={}", event.getSenderId(), recipientDestination);
			
			// Sender'a anlik push
			String senderDestination = WebSocketChannels.dm(event.getSenderId());
			messagingTemplate.convertAndSend(senderDestination, dto);
			log.debug("DM mesajı WS push: senderId={}, dest={}", event.getSenderId(), senderDestination);
		} catch (Exception e) {
			log.error("DM message WS push failed messageId={} conversationId={} exceptionType={}",
			          event.getMessageId(), event.getConversationId(), e.getClass().getSimpleName());
		}
		refreshUnreadBadge(event.getRecipientId());


	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void onDmMessageRead(DmMessageReadEvent event) {
		if (event == null || !accountDeliveryFence.canDeliver(event.readerId(), java.util.List.of(event.readerId()))) return;
		refreshUnreadBadge(event.readerId());
	}

	private void refreshUnreadBadge(UUID userId) {
		try {
			long unread = messageRepository.countByRecipientIdAndReadAtIsNull(userId);
			String badgeDestination = WebSocketChannels.dmBadge(userId);
			messagingTemplate.convertAndSend(badgeDestination, unread);
			log.debug("DM unread badge WS push: userId={}, badge={}", userId, unread);
		} catch (Exception exception) {
			log.warn("DM unread badge refresh failed userId={} exceptionType={}",
			         userId, exception.getClass().getSimpleName());
		}
	}

}
