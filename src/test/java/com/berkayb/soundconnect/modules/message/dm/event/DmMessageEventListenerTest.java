package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.helper.DmBadgeCacheHelper;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
class DmMessageEventListenerTest {
	
	@Test
	@DisplayName("onDmMessaggeSent: mesaj bulunur → DTO map, iki DM push, badge hesap + cache set + badge push")
	void onDmMessageSent_ok() {
		// Mocks
		SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
		DMMessageMapper messageMapper = mock(DMMessageMapper.class);
		DMMessageRepository messageRepository = mock(DMMessageRepository.class);
		DmBadgeCacheHelper badgeCacheHelper = mock(DmBadgeCacheHelper.class);
		
		DmMessageEventListener listener = new DmMessageEventListener(
				messagingTemplate, messageMapper, messageRepository, badgeCacheHelper
		);
		
		// Given
		UUID conversationId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID recipientId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		
		DMMessage entity = DMMessage.builder()
		                            .id(messageId)
		                            .conversationId(conversationId)
		                            .senderId(senderId)
		                            .recipientId(recipientId)
		                            .content("hi")
		                            .messageType("text")
		                            .build();
		
		when(messageRepository.findById(messageId)).thenReturn(Optional.of(entity));
		
		DMMessageResponseDto dto = new DMMessageResponseDto(
				messageId, conversationId, senderId, recipientId,
				"hi", "text", LocalDateTime.now(), null, null
		);
		when(messageMapper.toResponseDto(entity)).thenReturn(dto);
		
		// unread hesap (recipient tarafında aynı konuşmada okunmamışlar)
		when(messageRepository.findByConversationIdAndRecipientIdAndReadAtIsNull(conversationId, recipientId))
				.thenReturn(List.of(new DMMessage(), new DMMessage())); // 2 unread varsayalım
		
		// badge cache okuma → badge push için 2 (güncel cache)
		when(badgeCacheHelper.getCacheUnread(recipientId)).thenReturn(2L);
		
		DmMessageSentEvent event = DmMessageSentEvent.builder()
		                                             .messageId(messageId)
		                                             .conversationId(conversationId)
		                                             .senderId(senderId)
		                                             .recipientId(recipientId)
		                                             .content("hi")
		                                             .messageType("text")
		                                             .sentAt(LocalDateTime.now())
		                                             .build();
		
		// When
		listener.onDmMessaggeSent(event);
		
		// Then: iki DM kanalı push (sender + recipient)
		String recipientDest = WebSocketChannels.dm(recipientId);
		String senderDest = WebSocketChannels.dm(senderId);
		verify(messagingTemplate).convertAndSend(eq(recipientDest), eq(dto));
		verify(messagingTemplate).convertAndSend(eq(senderDest), eq(dto));
		
		// Then: unread say → cache set(2), sonra badge push
		verify(badgeCacheHelper).setUnread(recipientId, 2L);
		
		String badgeDest = WebSocketChannels.dmBadge(recipientId);
		verify(messagingTemplate).convertAndSend(eq(badgeDest), eq(2L));
	}
	
	@Test
	@DisplayName("onDmMessaggeSent: mesaj yok → WS/Badge çalışmaz (early return)")
	void onDmMessageSent_messageMissing() {
		SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
		DMMessageMapper messageMapper = mock(DMMessageMapper.class);
		DMMessageRepository messageRepository = mock(DMMessageRepository.class);
		DmBadgeCacheHelper badgeCacheHelper = mock(DmBadgeCacheHelper.class);
		
		DmMessageEventListener listener = new DmMessageEventListener(
				messagingTemplate, messageMapper, messageRepository, badgeCacheHelper
		);
		
		UUID messageId = UUID.randomUUID();
		when(messageRepository.findById(messageId)).thenReturn(Optional.empty());
		
		DmMessageSentEvent event = DmMessageSentEvent.builder()
		                                             .messageId(messageId)
		                                             .conversationId(UUID.randomUUID())
		                                             .senderId(UUID.randomUUID())
		                                             .recipientId(UUID.randomUUID())
		                                             .content("x")
		                                             .messageType("text")
		                                             .sentAt(LocalDateTime.now())
		                                             .build();
		
		listener.onDmMessaggeSent(event);
		
		verifyNoInteractions(messageMapper);
		verify(badgeCacheHelper, never()).setUnread(any(), anyLong());
		verifyNoInteractions(messagingTemplate);
	}
}