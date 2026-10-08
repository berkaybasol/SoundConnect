package com.berkayb.soundconnect.modules.message.dm.service;

import com.berkayb.soundconnect.modules.message.dm.dto.request.DMMessageRequestDto;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMMessageResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.event.DmMessageEventPublisher;
import com.berkayb.soundconnect.modules.message.dm.event.DmMessageReadEvent;
import com.berkayb.soundconnect.modules.message.dm.event.DmMessageSentEvent;
import com.berkayb.soundconnect.modules.message.dm.mapper.DMMessageMapper;
import com.berkayb.soundconnect.modules.message.dm.abuse.DmRateLimitGuard;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.user.support.AccountDeliveryFence;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
	private final DmNotificationService dmNotificationService;
	private final DmSendReceiptStore sendReceipts;
	private final DmRateLimitGuard rateLimit;

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

		// Stable newest-first pages and bounded work, even if a caller requests
		// an unbounded or very large page. Existing mobile clients use size 30.
		int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
		int size = pageable.isPaged() ? Math.min(100, pageable.getPageSize()) : 30;
		return messageRepository.findByConversationId(conversationId,
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
				.map(messageMapper::toResponseDto);
	}


	// mesaji gonderir, conversation'i gunceller
	@Override
	@Transactional
	public DMMessageResponseDto sendMessage(DMMessageRequestDto requestDto, UUID senderId) {
		if (requestDto == null || senderId == null || requestDto.recipientId() == null
				|| requestDto.conversationId() == null || requestDto.content() == null
				|| requestDto.content().isBlank() || requestDto.content().length() > DMMessageRequestDto.MAX_CONTENT_LENGTH
				|| (requestDto.messageType() != null && !"text".equals(requestDto.messageType())))
			throw new SoundConnectException(ErrorType.BAD_REQUEST);
		accountDeliveryFence.requireActive(List.of(senderId, requestDto.recipientId()));
		sendReceipts.lock(senderId, requestDto.clientMessageId());
		// conversation mevcut mu?
		DMConversation conversation = conversationRepository.findByIdForUpdate(requestDto.conversationId())
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

		var receipt = sendReceipts.find(senderId, requestDto.clientMessageId());
		if (receipt.isPresent()) {
			var prior = receipt.get();
			if (!prior.conversationId().equals(conversation.getId()) || !prior.recipientId().equals(expectedRecipientId))
				throw new SoundConnectException(ErrorType.DM_MESSAGE_IDEMPOTENCY_CONFLICT);
			var persisted = messageRepository.findById(prior.messageId())
					.filter(value -> value.getDeletedAt() == null)
					.orElseThrow(() -> new SoundConnectException(ErrorType.MESSAGE_NOT_FOUND));
			String type = requestDto.messageType() == null ? "text" : requestDto.messageType();
			if (!persisted.getSenderId().equals(senderId) || !persisted.getRecipientId().equals(expectedRecipientId)
					|| !persisted.getConversationId().equals(conversation.getId())
					|| !persisted.getContent().equals(requestDto.content()) || !persisted.getMessageType().equals(type))
				throw new SoundConnectException(ErrorType.DM_MESSAGE_IDEMPOTENCY_CONFLICT);
			return messageMapper.toResponseDto(persisted);
		}
		rateLimit.check(senderId, expectedRecipientId);

		// mesagi olustur
		DMMessage message = DMMessage.builder()
				.conversationId(conversation.getId())
				.senderId(senderId)
				.recipientId(requestDto.recipientId())
				.content(requestDto.content())
				.messageType(requestDto.messageType() == null ? "text" : requestDto.messageType())
				.build();

		// mesaji kaydet
		// Flush the source before the shared JDBC delivery policy reads it. The
		// message, inbox receipt and push job still commit or roll back together.
		messageRepository.saveAndFlush(message);
		sendReceipts.record(senderId, requestDto.clientMessageId(), conversation.getId(), expectedRecipientId, message.getId());

		// conversation'i guncelle (son mesaj tarihi ve lastMessageId)
		conversation.setLastMessageAt(LocalDateTime.now(ZoneOffset.UTC));
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

		dmNotificationService.persist(event);
		dmMessageEventPublisher.publishMessageSentEvent(event);

		// response dto'ya cevir
		return messageMapper.toResponseDto(message);
	}

	// Goruldu olarak isaretle
	@Override
	@Transactional
	public void markMessageAsRead(UUID messageId, UUID readerId) {
		accountDeliveryFence.requireActive(List.of(readerId));
		// Resolve only the immutable parent id before locking. Every DM mutation
		// and delivery admission locks conversation before message, preventing a
		// read/delete cycle while also fencing concurrent sends during moderation.
		UUID conversationId = messageRepository.findConversationIdByMessageId(messageId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MESSAGE_NOT_FOUND));
		DMConversation conversation = conversationRepository.findByIdForUpdate(conversationId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.CONVERSATION_NOT_FOUND));
		DMMessage message = messageRepository.findByIdForUpdate(messageId)
				.filter(value -> value.getDeletedAt() == null)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MESSAGE_NOT_FOUND));
		// yalnizca alici olan kisi okuyabilir
		if (!message.getRecipientId().equals(readerId)) {
			throw new SoundConnectException(ErrorType.NOT_AUTHORIZED);
		}
		if (!(conversation.getUserAId().equals(readerId) || conversation.getUserBId().equals(readerId))) {
			throw new SoundConnectException(ErrorType.NOT_PARTICIPANT_OF_CONVERSATION);
		}
		// daha once okunduysa tekrar setleme
		if (message.getReadAt() != null) {
			notificationService.markDmMessageAsRead(readerId, messageId);
			dmMessageEventPublisher.publishMessageReadEvent(
					new DmMessageReadEvent(message.getConversationId(), readerId));
			return; // zaten okunmus
		}
		message.setReadAt(LocalDateTime.now(ZoneOffset.UTC));
		messageRepository.saveAndFlush(message);

		// konusmanin "lastReadMessageId" guncellemesi (UI icin)
		conversation.setLastReadMessageId(message.getId());
		conversationRepository.save(conversation);

		notificationService.markDmMessageAsRead(readerId, messageId);
		dmMessageEventPublisher.publishMessageReadEvent(
				new DmMessageReadEvent(message.getConversationId(), readerId));

	}

	@Override
	@Transactional(readOnly = true)
	public long getUnreadCount(UUID userId) {
		return messageRepository.countByRecipientIdAndReadAtIsNull(userId);
	}
}
