package com.berkayb.soundconnect.modules.message.dm.event;

import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
	@DisplayName("onDmMessageSent: mesaj bulunur → DTO map, iki DM push ve DB-authoritative badge push")
	void onDmMessageSent_ok() {
		// Mocks
		SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
		DMMessageMapper messageMapper = mock(DMMessageMapper.class);
		DMMessageRepository messageRepository = mock(DMMessageRepository.class);
		NotificationProducer notificationProducer = mock(NotificationProducer.class);
		UserRepository userRepository = mock(UserRepository.class);
		PublicProfileResolverService publicProfileResolverService = mock(PublicProfileResolverService.class);
		
		DmMessageEventListener listener = new DmMessageEventListener(
				messagingTemplate, messageMapper, messageRepository, notificationProducer, userRepository,
				publicProfileResolverService
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
		when(userRepository.findById(senderId))
				.thenReturn(Optional.of(User.builder()
				                            .username("basol")
				                            .profilePicture("https://cdn.soundconnect.test/user-fallback.jpg")
				                            .build()));
		when(publicProfileResolverService.resolveByUserId(senderId))
				.thenReturn(new UserProfilesResolveResponseDto(
						senderId,
						List.of(new UserProfileTargetDto(
								"MUSICIAN",
								UUID.randomUUID(),
								"Basol",
								"https://cdn.soundconnect.test/basol.jpg"
						))
				));
		
		when(messageRepository.countByRecipientIdAndReadAtIsNull(recipientId)).thenReturn(2L);
		
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
		listener.onDmMessageSent(event);
		
		// Then: iki DM kanalı push (sender + recipient)
		String recipientDest = WebSocketChannels.dm(recipientId);
		String senderDest = WebSocketChannels.dm(senderId);
		verify(messagingTemplate).convertAndSend(eq(recipientDest), eq(dto));
		verify(messagingTemplate).convertAndSend(eq(senderDest), eq(dto));
		
		// Then: indexed DB total unread count is pushed directly.
		String badgeDest = WebSocketChannels.dmBadge(recipientId);
		verify(messagingTemplate).convertAndSend(eq(badgeDest), eq(2L));

		ArgumentCaptor<NotificationInboundEvent> notificationCaptor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);
		verify(notificationProducer).publish(notificationCaptor.capture());
		NotificationInboundEvent notification = notificationCaptor.getValue();
		assertThat(notification.recipientId()).isEqualTo(recipientId);
		assertThat(notification.type()).isEqualTo(NotificationType.DM_NEW_MESSAGE);
		assertThat(notification.title()).isEqualTo("Basol size bir mesaj gönderdi");
		assertThat(notification.message()).isEqualTo("hi");
		assertThat(notification.payload())
				.containsEntry("module", "DM")
				.containsEntry("conversationId", conversationId.toString())
				.containsEntry("messageId", messageId.toString())
				.containsEntry("senderId", senderId.toString())
				.containsEntry("senderUsername", "Basol")
				.containsEntry("senderAvatarUrl", "https://cdn.soundconnect.test/basol.jpg")
				.containsEntry("recipientId", recipientId.toString())
				.containsEntry("messageType", "text");
		assertThat(notification.payload()).doesNotContainKey("senderVisibilityMode");
	}

	@Test
	@DisplayName("onDmMessageSent: ghost sender snapshot uses canonical identity without avatar fallback")
	void onDmMessageSent_ghostSenderUsesCanonicalIdentityAndMarker() {
		SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
		DMMessageMapper messageMapper = mock(DMMessageMapper.class);
		DMMessageRepository messageRepository = mock(DMMessageRepository.class);
		NotificationProducer notificationProducer = mock(NotificationProducer.class);
		UserRepository userRepository = mock(UserRepository.class);
		PublicProfileResolverService publicProfileResolverService = mock(PublicProfileResolverService.class);
		DmMessageEventListener listener = new DmMessageEventListener(
				messagingTemplate,
				messageMapper,
				messageRepository,
				notificationProducer,
				userRepository,
				publicProfileResolverService
		);
		UUID conversationId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID recipientId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		DMMessage message = DMMessage.builder()
				.id(messageId)
				.conversationId(conversationId)
				.senderId(senderId)
				.recipientId(recipientId)
				.content("hello")
				.messageType("text")
				.build();
		when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
		when(messageMapper.toResponseDto(message)).thenReturn(new DMMessageResponseDto(
				messageId,
				conversationId,
				senderId,
				recipientId,
				"hello",
				"text",
				LocalDateTime.now(),
				null,
				null
		));
		when(publicProfileResolverService.resolveByUserId(senderId))
				.thenReturn(new UserProfilesResolveResponseDto(
						senderId,
						List.of(new UserProfileTargetDto(
								"LISTENER",
								UUID.randomUUID(),
								"ghosthandle",
								null,
								ListenerVisibilityMode.GHOST
						))
				));
		DmMessageSentEvent event = DmMessageSentEvent.builder()
				.messageId(messageId)
				.conversationId(conversationId)
				.senderId(senderId)
				.recipientId(recipientId)
				.content("hello")
				.messageType("text")
				.sentAt(LocalDateTime.now())
				.build();

		listener.onDmMessageSent(event);

		ArgumentCaptor<NotificationInboundEvent> notificationCaptor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);
		verify(notificationProducer).publish(notificationCaptor.capture());
		assertThat(notificationCaptor.getValue().payload())
				.containsEntry("senderUsername", "ghosthandle")
				.containsEntry("senderAvatarUrl", "")
				.containsEntry("senderVisibilityMode", "GHOST");
		verifyNoInteractions(userRepository);
	}

	@Test
	@DisplayName("onDmMessageSent: identity resolver failure never falls back to legacy user identity")
	void onDmMessageSent_identityResolverFailurePublishesSanitizedNotification() {
		SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
		DMMessageMapper messageMapper = mock(DMMessageMapper.class);
		DMMessageRepository messageRepository = mock(DMMessageRepository.class);
		NotificationProducer notificationProducer = mock(NotificationProducer.class);
		UserRepository userRepository = mock(UserRepository.class);
		PublicProfileResolverService publicProfileResolverService = mock(PublicProfileResolverService.class);
		DmMessageEventListener listener = new DmMessageEventListener(
				messagingTemplate,
				messageMapper,
				messageRepository,
				notificationProducer,
				userRepository,
				publicProfileResolverService
		);
		UUID conversationId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID recipientId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		DMMessage message = DMMessage.builder()
				.id(messageId)
				.conversationId(conversationId)
				.senderId(senderId)
				.recipientId(recipientId)
				.content("hello")
				.messageType("text")
				.build();
		when(messageRepository.findById(messageId)).thenReturn(Optional.of(message));
		when(messageMapper.toResponseDto(message)).thenReturn(new DMMessageResponseDto(
				messageId, conversationId, senderId, recipientId,
				"hello", "text", LocalDateTime.now(), null, null
		));
		when(publicProfileResolverService.resolveByUserId(senderId))
				.thenThrow(new IllegalStateException("identity store unavailable"));
		when(userRepository.findById(senderId)).thenReturn(Optional.of(User.builder()
				.username("legacy-display-name")
				.profilePicture("https://cdn.example/legacy-avatar.jpg")
				.build()));
		DmMessageSentEvent event = DmMessageSentEvent.builder()
				.messageId(messageId)
				.conversationId(conversationId)
				.senderId(senderId)
				.recipientId(recipientId)
				.content("hello")
				.messageType("text")
				.sentAt(LocalDateTime.now())
				.build();

		listener.onDmMessageSent(event);

		ArgumentCaptor<NotificationInboundEvent> notificationCaptor =
				ArgumentCaptor.forClass(NotificationInboundEvent.class);
		verify(notificationProducer).publish(notificationCaptor.capture());
		NotificationInboundEvent notification = notificationCaptor.getValue();
		assertThat(notification.title()).isEqualTo("Bir kullanici size bir mesaj gönderdi");
		assertThat(notification.payload())
				.containsEntry("senderId", senderId.toString())
				.containsEntry("senderUsername", "Bir kullanici")
				.containsEntry("senderAvatarUrl", "")
				.doesNotContainKey("senderVisibilityMode");
		assertThat(notification.payload().values())
				.doesNotContain("legacy-display-name", "https://cdn.example/legacy-avatar.jpg");
		verifyNoInteractions(userRepository);
	}
	
	@Test
	@DisplayName("onDmMessageSent: mesaj yok → WS/Badge çalışmaz (early return)")
	void onDmMessageSent_messageMissing() {
		SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
		DMMessageMapper messageMapper = mock(DMMessageMapper.class);
		DMMessageRepository messageRepository = mock(DMMessageRepository.class);
		NotificationProducer notificationProducer = mock(NotificationProducer.class);
		UserRepository userRepository = mock(UserRepository.class);
		PublicProfileResolverService publicProfileResolverService = mock(PublicProfileResolverService.class);
		
		DmMessageEventListener listener = new DmMessageEventListener(
				messagingTemplate, messageMapper, messageRepository, notificationProducer, userRepository,
				publicProfileResolverService
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
		
		listener.onDmMessageSent(event);
		
		verifyNoInteractions(messageMapper);
		verifyNoInteractions(messagingTemplate);
		verifyNoInteractions(notificationProducer);
		verifyNoInteractions(userRepository);
		verifyNoInteractions(publicProfileResolverService);
	}

	@Test
	@DisplayName("DM realtime event handlers run only after transaction commit")
	void realtimeHandlers_areAfterCommit() throws Exception {
		TransactionalEventListener sentListener = DmMessageEventListener.class
				.getMethod("onDmMessageSent", DmMessageSentEvent.class)
				.getAnnotation(TransactionalEventListener.class);
		TransactionalEventListener readListener = DmMessageEventListener.class
				.getMethod("onDmMessageRead", DmMessageReadEvent.class)
				.getAnnotation(TransactionalEventListener.class);
		Transactional sentTransaction = DmMessageEventListener.class
				.getMethod("onDmMessageSent", DmMessageSentEvent.class)
				.getAnnotation(Transactional.class);

		assertThat(sentListener).isNotNull();
		assertThat(sentListener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
		assertThat(readListener).isNotNull();
		assertThat(readListener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
		assertThat(sentTransaction).isNotNull();
		assertThat(sentTransaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
	}

	@Test
	@DisplayName("read event refreshes the committed unread count and pushes the badge")
	void onDmMessageRead_refreshesBadge() {
		SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
		DMMessageMapper messageMapper = mock(DMMessageMapper.class);
		DMMessageRepository messageRepository = mock(DMMessageRepository.class);
		NotificationProducer notificationProducer = mock(NotificationProducer.class);
		UserRepository userRepository = mock(UserRepository.class);
		PublicProfileResolverService publicProfileResolverService = mock(PublicProfileResolverService.class);
		DmMessageEventListener listener = new DmMessageEventListener(
				messagingTemplate, messageMapper, messageRepository, notificationProducer,
				userRepository, publicProfileResolverService);
		UUID readerId = UUID.randomUUID();
		when(messageRepository.countByRecipientIdAndReadAtIsNull(readerId)).thenReturn(4L);

		listener.onDmMessageRead(new DmMessageReadEvent(UUID.randomUUID(), readerId));

		verify(messageRepository).countByRecipientIdAndReadAtIsNull(readerId);
		verify(messagingTemplate).convertAndSend(WebSocketChannels.dmBadge(readerId), 4L);
		verifyNoInteractions(notificationProducer);
	}
}
