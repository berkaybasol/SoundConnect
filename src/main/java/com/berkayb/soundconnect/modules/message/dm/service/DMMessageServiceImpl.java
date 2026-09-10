package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.event.DmMessageEventPublisher;
import com.berkayb.soundconnect.modules.message.dm.event.DmMessageReadEvent;
import com.berkayb.soundconnect.modules.message.dm.event.DmMessageSentEvent;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DMMessageServiceImpl implements DMMessageService {
	private final DMMessageRepository messageRepository;
	private final DMConversationRepository conversationRepository;
	private final DMMessageMapper messageMapper;
	private final DmMessageEventPublisher dmMessageEventPublisher;
	private final NotificationService notificationService;
	private final AccountDeliveryFence accountDeliveryFence;

	// belirli bir conversation'in tum mesajlarini gonderim sirasina gore doner.
	@Override
	public List<DMMessageResponseDto> getMessagesByConversationId(UUID conversationId) {
		// conversation mevcut degilse exception firlat
		DMConversation conversation = conversationRepository.findById(conversationId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.CONVERSATION_NOT_FOUND));

		// mesajlari sirali olarak cek ve dto'ya cevir
		return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)
				.stream()
				.map(messageMapper::toResponseDto)
				.collect(Collectors.toList());
	}

	@Override
	public Page<DMMessageResponseDto> getMessagesByConversationId(UUID conversationId, Pageable pageable) {
		conversationRepository.findById(conversationId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.CONVERSATION_NOT_FOUND));

		return messageRepository.findByConversationId(conversationId, pageable)
				.map(messageMapper::toResponseDto);
	}


	// mesaji gonderir, conversation'i gunceller
	@Override
	@Transactional
	public DMMessageResponseDto sendMessage(DMMessageRequestDto requestDto, UUID senderId) {
		if (requestDto == null || senderId == null || requestDto.recipientId() == null)
			throw new SoundConnectException(ErrorType.BAD_REQUEST);
		accountDeliveryFence.requireActive(List.of(senderId, requestDto.recipientId()));
		// conversation mevcut mu?
		DMConversation conversation = conversationRepository.findById(requestDto.conversationId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.CONVERSATION_NOT_FOUND));

		// sender bu conversation'un katilimcisi mi?
		if (!(conversation.getUserAId().equals(senderId) || conversation.getUserBId().equals(senderId))) {
			throw new SoundConnectException(ErrorType.NOT_PARTICIPANT_OF_CONVERSATION);
		}

		// sender ve recipient ayni mi?
		if (senderId.equals(requestDto.recipientId())) {
			throw new SoundConnectException(ErrorType.CANNOT_DM_SELF);
		}

		UUID expectedRecipientId = conversation.getUserAId().equals(senderId)
				? conversation.getUserBId()
				: conversation.getUserAId();
		if (!expectedRecipientId.equals(requestDto.recipientId())) {
			throw new SoundConnectException(ErrorType.NOT_PARTICIPANT_OF_CONVERSATION);
		}

		// mesagi olustur
		DMMessage message = DMMessage.builder()
				.conversationId(conversation.getId())
				.senderId(senderId)
				.recipientId(requestDto.recipientId())
				.content(requestDto.content())
				.messageType(requestDto.messageType() == null ? "text" : requestDto.messageType())
				.build();

		// mesaji kaydet
		messageRepository.save(message);

		// conversation'i guncelle (son mesaj tarihi ve lastMessageId)
		conversation.setLastMessageAt(LocalDateTime.now());
		conversation.setLastReadMessageId(null);
		conversationRepository.save(conversation);

		// Event Fire
		DmMessageSentEvent event = DmMessageSentEvent.builder()
		                                             .messageId(message.getId())
		                                             .conversationId(message.getConversationId())
		                                             .senderId(message.getSenderId())
		                                             .recipientId(message.getRecipientId())
		                                             .content(message.getContent())
		                                             .messageType(message.getMessageType())
		                                             .sentAt(message.getCreatedAt())
		                                             .build();

		dmMessageEventPublisher.publishMessageSentEvent(event);

		// response dto'ya cevir
		return messageMapper.toResponseDto(message);
	}

	// Goruldu olarak isaretle
	@Override
	@Transactional
	public void markMessageAsRead(UUID messageId, UUID readerId) {
		accountDeliveryFence.requireActive(List.of(readerId));
		// mesaji bul
		DMMessage message = messageRepository.findById(messageId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MESSAGE_NOT_FOUND));
		// yalnizca alici olan kisi okuyabilir
		if (!message.getRecipientId().equals(readerId)) {
			throw new SoundConnectException(ErrorType.NOT_AUTHORIZED);
		}
		DMConversation conversation = conversationRepository.findById(message.getConversationId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.CONVERSATION_NOT_FOUND));
		if (!(conversation.getUserAId().equals(readerId) || conversation.getUserBId().equals(readerId))) {
			throw new SoundConnectException(ErrorType.NOT_PARTICIPANT_OF_CONVERSATION);
		}
		// daha once okunduysa tekrar setleme
		if (message.getReadAt() != null) {
			notificationService.markDmConversationAsRead(readerId, message.getConversationId());
			dmMessageEventPublisher.publishMessageReadEvent(
					new DmMessageReadEvent(message.getConversationId(), readerId));
			return; // zaten okunmus
		}
		message.setReadAt(LocalDateTime.now());
		messageRepository.save(message);

		// konusmanin "lastReadMessageId" guncellemesi (UI icin)
		conversation.setLastReadMessageId(message.getId());
		conversationRepository.save(conversation);

		notificationService.markDmConversationAsRead(readerId, message.getConversationId());
		dmMessageEventPublisher.publishMessageReadEvent(
				new DmMessageReadEvent(message.getConversationId(), readerId));

	}

	@Override
	@Transactional(readOnly = true)
	public long getUnreadCount(UUID userId) {
		return messageRepository.countByRecipientIdAndReadAtIsNull(userId);
	}
}
