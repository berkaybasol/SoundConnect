package com.berkayb.soundconnect.modules.message.dm.dto.request;

import java.util.UUID;

public record DMMessageRequestDto(
		UUID conversationId,
		UUID recipientId,
		String content,
		String messageType
		) {
}