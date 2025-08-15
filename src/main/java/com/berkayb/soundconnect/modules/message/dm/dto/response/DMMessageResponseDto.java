package com.berkayb.soundconnect.modules.message.dm.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record DMMessageResponseDto(
		UUID messageId,
		UUID conversationId,
		UUID senderId,
		UUID recipientId,
		String content,
		String messageType,
		LocalDateTime sentAt,
		LocalDateTime readAt,
		LocalDateTime deletedAt
) {
}