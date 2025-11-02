package com.berkayb.soundconnect.modules.tablegroup.chat.service;

import com.berkayb.soundconnect.modules.tablegroup.chat.cache.TableGroupChatUnreadHelper;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.request.TableGroupMessageRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.modules.tablegroup.chat.mapper.TableGroupMessageMapper;
import com.berkayb.soundconnect.modules.tablegroup.chat.repository.TableGroupMessageRepository;
import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.repository.TableGroupRepository;
import com.berkayb.soundconnect.modules.tablegroup.support.TableGroupEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.realtime.WebSocketChannels;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TableGroupChatServiceImpl implements TableGroupChatService {
	
	private final TableGroupMessageRepository messageRepository;
	private final TableGroupMessageMapper messageMapper;
	private final SimpMessagingTemplate messagingTemplate;
	private final TableGroupEntityFinder tableGroupEntityFinder;
	private final TableGroupChatUnreadHelper unreadHelper;
	
	/**
	 * Kurallar:
	 * - Masa durumu ACTIVE olmali
	 * - masa suresi (expiresAt) gecmemis olmali
	 * - kullanici mesaj gondermek icin accepted durumda olmali
	 * Mesaj kaydedilir unread counter herkes icin update edilir ve broadcast yapilir.
	 */
	@Override
	@Transactional
	public TableGroupMessageResponseDto sendMessage(UUID senderId, UUID tableGroupId, TableGroupMessageRequestDto requestDto) {
		
		// masa var mi?
		TableGroup tableGroup = tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId);
		
		// masa aktif mi?
		if (tableGroup.getStatus() != TableGroupStatus.ACTIVE) {
			throw new SoundConnectException(ErrorType.TABLE_GROUP_NOT_FOUND, "Masa aktif degil veya iptal edildi");
		}
		
		// suresi gecti mi?
		if (tableGroup.getExpiresAt() != null && tableGroup.getExpiresAt().isBefore(LocalDateTime.now())) {
			throw new SoundConnectException(ErrorType.TABLE_END_DATE_PASSED, "Masa suresi doldu, artik mesaj gonderilemez");
		}
		
		// kullanici masada accepted mi?
		boolean isAcceptedParticipant = tableGroup.getParticipants().stream()
		                                          .anyMatch(p ->
				                                                    p.getUserId().equals(senderId)
						                                                    && p.getStatus() == ParticipantStatus.ACCEPTED
		                                          );
		// gpt ekletti :D
		if (!isAcceptedParticipant) {
			// bu noktada birisi WS'den brute-force denerse blocklanir.
			log.warn(
					"UNAUTHORIZED CHAT SEND attempt: userId={} tableGroupId={} status=DENIED",
					senderId, tableGroupId
			);
			throw new SoundConnectException(ErrorType.UNAUTHORIZED,
			                                "Bu masada konusma yetkin yok");
		}
		
		// mesaj tipi null geldiyse TEXT yap
		MessageType messageType = requestDto.messageType() != null
				? requestDto.messageType()
				: MessageType.TEXT;
		
		// Entity olustur
		TableGroupMessage message = TableGroupMessage.builder()
				.tableGroupId(tableGroupId)
				.senderId(senderId)
				.content(requestDto.content())
				.messageType(messageType)
				.deletedAt(null)
				.build();
		// kaydet
		messageRepository.save(message);
		
		// Unread badge i guncelle:
		// masadaki herkes icin unread++ ama gonderen haric
		tableGroup.getParticipants().stream()
				.filter(p -> p.getStatus() == ParticipantStatus.ACCEPTED)
				.map(p-> p.getUserId())
				.filter(userId -> !userId.equals(senderId))
				.forEach(userId -> unreadHelper.incrementUnread(userId, tableGroupId));
		
		// DTO'ya cevir
		TableGroupMessageResponseDto dto = messageMapper.toResponseDto(message);
		
		// WS broadcast
		// tum masaya yayin: /topic/table-group/{tableGroupId)
		String destination = WebSocketChannels.tableGroup(tableGroupId);
		try {
			messagingTemplate.convertAndSend(destination, dto);
			
			log.debug("TABLE-GROUP CHAT WS push -> dest={}, sender={}, msgId={}",
			          destination,senderId,message.getId());
			
		} catch (Exception e) {
			log.warn("TABLE-GROUP CHAT WS push FAILED -> dest={}, err={}", destination, e.toString());
		}
		return dto;
	}
	
	/*
	- Masa sohbet gecmisini getirir (pageable)
		Kurallar:
		- Kullanici accepted olmali
		- once unread badge sifirlanir cunku kullanici sohbeti okudu sayiyoz iste :D
		- soft-delete edilmis mesajlar (deleletedAt != null) gosterilmez
	 */
	@Override
	@Transactional (readOnly = true)
	public Page<TableGroupMessageResponseDto> getMessages(UUID requesterId, UUID tableGroupId, Pageable pageable) {
		// unread badge'i sifirla
		unreadHelper.resetUnread(requesterId, tableGroupId);
		
		// masa var mi?
		TableGroup tableGroup = tableGroupEntityFinder.GetTableGroupByTableGroupId(tableGroupId);
		
		// bu kullanici masanin icinde mi ve erisim izni var mi?
		boolean isAllowed = tableGroup.getParticipants().stream()
				.anyMatch(p->
						p.getUserId().equals(requesterId)
				&& p.getStatus() == ParticipantStatus.ACCEPTED
				);
				if (!isAllowed) {
					log.warn(
							"UNAUTHORIZED CHAT HISTORY READ attempt: userId={} tableGroupId={}",
							requesterId, tableGroupId
					);
					throw new SoundConnectException(ErrorType.UNAUTHORIZED,"Bu masanin sohbetine erisimin yok");
				}
		// mesajlari db'den cek
		return messageRepository.findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtAsc(tableGroupId,pageable)
				.map(messageMapper::toResponseDto);
	}
}