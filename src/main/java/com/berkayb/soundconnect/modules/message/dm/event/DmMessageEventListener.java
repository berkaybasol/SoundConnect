package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.helper.DmBadgeCacheHelper;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Dm mesaj event'lerini dinleyip, WebSocket/STOMP uzerinden anlik push yapan subscriber.
 * Sadece push ve badge isi yapar business loggic icermez
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DmMessageEventListener {
	private final SimpMessagingTemplate messagingTemplate;
	private final DMMessageMapper messageMapper;
	private final DMMessageRepository messageRepository;
	private final DmBadgeCacheHelper badgeCacheHelper;
	
	@EventListener
	public void onDmMessaggeSent (DmMessageSentEvent event) {
		try {
			// eventteki bilgiden DMMessage entity'sini DB'den cek (responseDto icin)
			var msg = messageRepository.findById(event.getMessageId())
					.orElse(null);
			if (msg == null) {
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
			log.error("DM mesajı WS push FAILED! event={}, err={}", event, e.toString());
		}
		long unread = messageRepository.findByConversationIdAndRecipientIdAndReadAtIsNull(
				event.getConversationId(), event.getRecipientId()
		).size(); // BURDASIN
	}
}