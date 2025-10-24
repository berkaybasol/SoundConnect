package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.event.DmMessageEventPublisher;
import com.berkayb.soundconnect.modules.message.dm.helper.DmBadgeCacheHelper;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class DMMessageServiceImplTest {
	
	@InjectMocks
	DMMessageServiceImpl service;
	
	@Mock DMMessageRepository messageRepository;
	@Mock DMConversationRepository conversationRepository;
	@Mock DMMessageMapper messageMapper;
	@Mock DmMessageEventPublisher eventPublisher;
	@Mock DmBadgeCacheHelper badgeCacheHelper;
	@Mock SimpMessagingTemplate messagingTemplate;
	
	UUID conversationId;
	UUID senderId;
	UUID recipientId;
	
	DMConversation conversation;
	
	@BeforeEach
	void init() {
		conversationId = UUID.randomUUID();
		senderId = UUID.randomUUID();
		recipientId = UUID.randomUUID();
		conversation = DMConversation.builder()
		                             .id(conversationId)
		                             .userAId(senderId)
		                             .userBId(recipientId)
		                             .build();
	}
	
	@Test
	@DisplayName("sendMessage: happy path → kaydet, conversation.lastMessageAt güncelle, event publish")
	void sendMessage_happyPath() {
		// given
		DMMessageRequestDto req = new DMMessageRequestDto(conversationId, recipientId, "hey", "text");
		when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
		
		// save edilen mesaja id ve createdAt setleyelim (auditing yok çünkü unit test)
		Mockito.lenient().when(messageRepository.save(any(DMMessage.class)))
		       .thenAnswer(inv -> {
			       DMMessage m = inv.getArgument(0);
			       m.setId(UUID.randomUUID());
			       // createdAt BaseEntity'de; yoksa mapper sentAt üretmek için null olabilir, sorun değil.
			       // İstersen createdAt'i taklit et:
			       // Reflection ya da setter varsa set edebilirsin; biz burada DTO'yu mapper'da simüle edeceğiz.
			       return m;
		       });
		
		DMMessageResponseDto mapped =
				new DMMessageResponseDto(UUID.randomUUID(), conversationId, senderId, recipientId,
				                         "hey", "text", LocalDateTime.now(), null, null);
		when(messageMapper.toResponseDto(any(DMMessage.class))).thenReturn(mapped);
		
		// when
		DMMessageResponseDto resp = service.sendMessage(req, senderId);
		
		// then
		assertThat(resp.content()).isEqualTo("hey");
		verify(messageRepository, times(1)).save(any(DMMessage.class));
		verify(conversationRepository, times(1)).save(argThat(conv ->
				                                                      conv.getId().equals(conversationId) && conv.getLastMessageAt() != null && conv.getLastReadMessageId() == null
		));
		verify(eventPublisher, times(1)).publishMessageSentEvent(any());
		verifyNoInteractions(badgeCacheHelper); // send tarafında badgeCache yok
	}
	
	@Test
	@DisplayName("sendMessage: conversation yoksa SoundConnectException fırlatır")
	void sendMessage_conversationNotFound() {
		DMMessageRequestDto req = new DMMessageRequestDto(conversationId, recipientId, "x", "text");
		when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());
		
		assertThrows(SoundConnectException.class, () -> service.sendMessage(req, senderId));
		
		verify(messageRepository, never()).save(any());
		verify(eventPublisher, never()).publishMessageSentEvent(any());
	}
	
	@Test
	@DisplayName("sendMessage: sender participant değilse hata")
	void sendMessage_notParticipant() {
		// sender bu konuşmada değil
		DMConversation otherConv = DMConversation.builder()
		                                         .id(conversationId)
		                                         .userAId(UUID.randomUUID())
		                                         .userBId(UUID.randomUUID())
		                                         .build();
		when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(otherConv));
		
		DMMessageRequestDto req = new DMMessageRequestDto(conversationId, recipientId, "x", "text");
		
		assertThrows(SoundConnectException.class, () -> service.sendMessage(req, senderId));
		
		verify(messageRepository, never()).save(any());
		verify(eventPublisher, never()).publishMessageSentEvent(any());
	}
	
	@Test
	@DisplayName("sendMessage: self-DM engellenmeli")
	void sendMessage_selfDmNotAllowed() {
		when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
		
		DMMessageRequestDto req = new DMMessageRequestDto(conversationId, senderId, "x", "text"); // recipient = sender
		
		assertThrows(SoundConnectException.class, () -> service.sendMessage(req, senderId));
		
		verify(messageRepository, never()).save(any());
		verify(eventPublisher, never()).publishMessageSentEvent(any());
	}
	
	@Test
	@DisplayName("getMessagesByConversationId: happy path → sırayla DTO döner")
	void getMessagesByConversationId_ok() {
		when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
		
		DMMessage m1 = DMMessage.builder()
		                        .id(UUID.randomUUID())
		                        .conversationId(conversationId)
		                        .senderId(senderId)
		                        .recipientId(recipientId)
		                        .content("a")
		                        .messageType("text")
		                        .build();
		DMMessage m2 = DMMessage.builder()
		                        .id(UUID.randomUUID())
		                        .conversationId(conversationId)
		                        .senderId(recipientId)
		                        .recipientId(senderId)
		                        .content("b")
		                        .messageType("text")
		                        .build();
		
		when(messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId))
				.thenReturn(List.of(m1, m2));
		
		when(messageMapper.toResponseDto(m1)).thenReturn(
				new DMMessageResponseDto(m1.getId(), conversationId, senderId, recipientId, "a", "text",
				                         LocalDateTime.now(), null, null));
		when(messageMapper.toResponseDto(m2)).thenReturn(
				new DMMessageResponseDto(m2.getId(), conversationId, recipientId, senderId, "b", "text",
				                         LocalDateTime.now(), null, null));
		
		List<DMMessageResponseDto> out = service.getMessagesByConversationId(conversationId);
		
		assertThat(out).hasSize(2);
		assertThat(out.get(0).content()).isEqualTo("a");
		assertThat(out.get(1).content()).isEqualTo("b");
	}
	
	@Test
	@DisplayName("getMessagesByConversationId: conversation yoksa hata")
	void getMessagesByConversationId_convNotFound() {
		when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());
		assertThrows(SoundConnectException.class, () -> service.getMessagesByConversationId(conversationId));
		verify(messageRepository, never()).findByConversationIdOrderByCreatedAtAsc(any());
	}
	
	@Test
	@DisplayName("markMessageAsRead: happy path → readAt set, lastReadMessageId güncelle, unread badge cache + WS push")
	void markMessageAsRead_ok() {
		UUID messageId = UUID.randomUUID();
		
		DMMessage msg = DMMessage.builder()
		                         .id(messageId)
		                         .conversationId(conversationId)
		                         .senderId(senderId)
		                         .recipientId(recipientId) // reader = recipient
		                         .content("x")
		                         .messageType("text")
		                         .build();
		
		when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));
		when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
		
		// unread sayımı (reader'ın tüm unread'leri)
		when(messageRepository.findByRecipientIdAndReadAtIsNull(recipientId)).thenReturn(List.of());
		when(badgeCacheHelper.getCacheUnread(recipientId)).thenReturn(0L);
		
		// when
		service.markMessageAsRead(messageId, recipientId);
		
		// then
		verify(messageRepository).save(argThat(saved -> saved.getId().equals(messageId) && saved.getReadAt() != null));
		verify(conversationRepository).save(argThat(conv -> conv.getLastReadMessageId() != null));
		
		verify(badgeCacheHelper).setUnread(recipientId, 0L);
		String dest = WebSocketChannels.dmBadge(recipientId);
		verify(messagingTemplate).convertAndSend(eq(dest), any(Object.class));
		
	}
	
	@Test
	@DisplayName("markMessageAsRead: sadece recipient okuyabilir")
	void markMessageAsRead_notRecipient() {
		UUID messageId = UUID.randomUUID();
		
		DMMessage msg = DMMessage.builder()
		                         .id(messageId)
		                         .conversationId(conversationId)
		                         .senderId(senderId)
		                         .recipientId(recipientId)
		                         .content("x")
		                         .messageType("text")
		                         .build();
		
		when(messageRepository.findById(messageId)).thenReturn(Optional.of(msg));
		
		assertThrows(SoundConnectException.class, () -> service.markMessageAsRead(messageId, senderId));
		
		verify(messageRepository, never()).save(any());
		verify(badgeCacheHelper, never()).setUnread(any(), anyLong());
		verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
		
	}
	
	@Test
	@DisplayName("markMessageAsRead: mesaj yoksa hata")
	void markMessageAsRead_messageNotFound() {
		UUID messageId = UUID.randomUUID();
		when(messageRepository.findById(messageId)).thenReturn(Optional.empty());
		
		assertThrows(SoundConnectException.class, () -> service.markMessageAsRead(messageId, recipientId));
		
		verifyNoInteractions(badgeCacheHelper, messagingTemplate);
	}
}