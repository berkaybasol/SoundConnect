package com.berkayb.soundconnect.modules.tablegroup.chat.service;

import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.request.TableGroupMessageRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.chat.mapper.TableGroupMessageMapper;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroupParticipant;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TableGroup chat business kurallari icin unit test.
 */
@ExtendWith(MockitoExtension.class)
class TableGroupChatServiceImplTest {
	
	@Mock
	private TableGroupMessageRepository messageRepository;
	
	@Mock
	private TableGroupMessageMapper messageMapper;
	
	@Mock
	private SimpMessagingTemplate messagingTemplate;
	
	@Mock
	private TableGroupEntityFinder tableGroupEntityFinder;
	
	@Mock
	private TableGroupChatUnreadHelper unreadHelper;
	
	@InjectMocks
	private TableGroupChatServiceImpl chatService;
	
	private TableGroup createActiveGroupWithAcceptedParticipants(UUID tableGroupId, UUID senderId, UUID otherUserId) {
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .maxPersonCount(4)
		                             .genderPrefs(List.of("MALE", "FEMALE"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(LocalDateTime.now().plusHours(2))
		                             .status(TableGroupStatus.ACTIVE)
		                             .participants(new HashSet<>())
		                             .build();
		
		group.getParticipants().add(
				TableGroupParticipant.builder()
				                     .userId(senderId)
				                     .status(ParticipantStatus.ACCEPTED)
				                     .joinedAt(LocalDateTime.now())
				                     .build()
		);
		group.getParticipants().add(
				TableGroupParticipant.builder()
				                     .userId(otherUserId)
				                     .status(ParticipantStatus.ACCEPTED)
				                     .joinedAt(LocalDateTime.now())
				                     .build()
		);
		
		return group;
	}
	
	// -------------------- sendMessage --------------------
	
	@Test
	void sendMessage_whenSenderIsAcceptedAndTableActive_shouldSaveMessageAndIncrementUnreadForOthers() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		UUID otherUserId = UUID.randomUUID();
		
		TableGroup group = createActiveGroupWithAcceptedParticipants(tableGroupId, senderId, otherUserId);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(group);
		
		TableGroupMessageRequestDto request = new TableGroupMessageRequestDto(
				"kanka nerdesiniz",
				MessageType.TEXT
		);
		
		TableGroupMessage saved = TableGroupMessage.builder()
		                                           .tableGroupId(tableGroupId)
		                                           .senderId(senderId)
		                                           .content(request.content())
		                                           .messageType(MessageType.TEXT)
		                                           .deletedAt(null)
		                                           .build();
		
		when(messageRepository.save(any(TableGroupMessage.class)))
				.thenReturn(saved);
		
		TableGroupMessageResponseDto dto = new TableGroupMessageResponseDto(
				UUID.randomUUID(),
				tableGroupId,
				senderId,
				request.content(),
				MessageType.TEXT,
				LocalDateTime.now(),
				null
		);
		when(messageMapper.toResponseDto(saved)).thenReturn(dto);
		
		// when
		TableGroupMessageResponseDto result = chatService.sendMessage(senderId, tableGroupId, request);
		
		// then
		assertThat(result).isEqualTo(dto);
		
		// unread: sadece diger accepted user icin increment
		verify(unreadHelper, times(1)).incrementUnread(otherUserId, tableGroupId);
		verify(unreadHelper, never()).incrementUnread(senderId, tableGroupId);
		
		// WS publish denemesi
		verify(messagingTemplate).convertAndSend(
				eq(WebSocketChannels.tableGroup(tableGroupId)),
				eq(dto)
		);
	}
	
	@Test
	void sendMessage_whenTableNotActive_shouldThrowTABLE_GROUP_NOT_FOUND() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .maxPersonCount(3)
		                             .genderPrefs(List.of("MALE", "FEMALE", "OTHER"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(LocalDateTime.now().plusHours(1))
		                             .status(TableGroupStatus.CANCELLED)
		                             .participants(new HashSet<>())
		                             .build();
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(group);
		
		TableGroupMessageRequestDto request = new TableGroupMessageRequestDto(
				"selam",
				MessageType.TEXT
		);
		
		// when / then
		assertThatThrownBy(() -> chatService.sendMessage(senderId, tableGroupId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.TABLE_GROUP_NOT_FOUND);
	}
	
	@Test
	void sendMessage_whenSenderNotAccepted_shouldThrowUnauthorized() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID senderId = UUID.randomUUID();
		
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .maxPersonCount(3)
		                             .genderPrefs(List.of("MALE", "FEMALE", "OTHER"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(LocalDateTime.now().plusHours(1))
		                             .status(TableGroupStatus.ACTIVE)
		                             .participants(new HashSet<>())
		                             .build();
		
		// Participant var ama PENDING / REJECTED vs olabilir, fark etmez, accepted degil
		group.getParticipants().add(
				TableGroupParticipant.builder()
				                     .userId(senderId)
				                     .status(ParticipantStatus.PENDING)
				                     .joinedAt(LocalDateTime.now())
				                     .build()
		);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(group);
		
		TableGroupMessageRequestDto request = new TableGroupMessageRequestDto(
				"selam",
				MessageType.TEXT
		);
		
		// when / then
		assertThatThrownBy(() -> chatService.sendMessage(senderId, tableGroupId, request))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED);
		
		verify(messageRepository, never()).save(any());
		verify(unreadHelper, never()).incrementUnread(any(), any());
	}
	
	// -------------------- getMessages --------------------
	
	@Test
	void getMessages_whenRequesterAccepted_shouldResetUnreadAndReturnPage() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID requesterId = UUID.randomUUID();
		Pageable pageable = PageRequest.of(0, 20);
		
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .maxPersonCount(3)
		                             .genderPrefs(List.of("MALE", "FEMALE", "OTHER"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(LocalDateTime.now().plusHours(1))
		                             .status(TableGroupStatus.ACTIVE)
		                             .participants(new HashSet<>())
		                             .build();
		
		group.getParticipants().add(
				TableGroupParticipant.builder()
				                     .userId(requesterId)
				                     .status(ParticipantStatus.ACCEPTED)
				                     .joinedAt(LocalDateTime.now())
				                     .build()
		);
		
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(group);
		
		TableGroupMessage msg = TableGroupMessage.builder()
		                                         .tableGroupId(tableGroupId)
		                                         .senderId(UUID.randomUUID())
		                                         .content("selamlar")
		                                         .messageType(MessageType.TEXT)
		                                         .deletedAt(null)
		                                         .build();
		
		Page<TableGroupMessage> msgPage = new PageImpl<>(List.of(msg));
		when(messageRepository.findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtAsc(tableGroupId, pageable))
				.thenReturn(msgPage);
		
		TableGroupMessageResponseDto dto = new TableGroupMessageResponseDto(
				UUID.randomUUID(),
				tableGroupId,
				msg.getSenderId(),
				msg.getContent(),
				msg.getMessageType(),
				LocalDateTime.now(),
				null
		);
		when(messageMapper.toResponseDto(msg)).thenReturn(dto);
		
		// when
		Page<TableGroupMessageResponseDto> result =
				chatService.getMessages(requesterId, tableGroupId, pageable);
		
		// then
		verify(unreadHelper).resetUnread(requesterId, tableGroupId);
		assertThat(result.getContent()).containsExactly(dto);
	}
	
	@Test
	void getMessages_whenRequesterNotAccepted_shouldThrowUnauthorized() {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID requesterId = UUID.randomUUID();
		Pageable pageable = PageRequest.of(0, 20);
		
		TableGroup group = TableGroup.builder()
		                             .ownerId(UUID.randomUUID())
		                             .maxPersonCount(3)
		                             .genderPrefs(List.of("MALE", "FEMALE", "OTHER"))
		                             .ageMin(20)
		                             .ageMax(30)
		                             .expiresAt(LocalDateTime.now().plusHours(1))
		                             .status(TableGroupStatus.ACTIVE)
		                             .participants(new HashSet<>())
		                             .build();
		
		// requester participant listesinde yok
		when(tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId))
				.thenReturn(group);
		
		// when / then
		assertThatThrownBy(() -> chatService.getMessages(requesterId, tableGroupId, pageable))
				.isInstanceOf(SoundConnectException.class)
				.hasFieldOrPropertyWithValue("errorType", ErrorType.UNAUTHORIZED);
		
		verify(unreadHelper).resetUnread(requesterId, tableGroupId); // method basinda yine de reset deniyor
		verify(messageRepository, never()).findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtAsc(any(), any());
	}
}