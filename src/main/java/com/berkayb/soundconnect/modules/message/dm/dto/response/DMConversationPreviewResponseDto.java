package com.berkayb.soundconnect.modules.message.dm.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record DMConversationPreviewResponseDto(
		UUID conversationId,
		UUID otherUserId, // konusmadaki karsi tarafin id
		String otherUsername, // konustugun kisinin adi
		String otherUserProfilePicture,
		String lastMessageContent,
		String lastMessageType,
		UUID lastMessageSenderId,
		LocalDateTime lastMessageAt,
		Boolean lastMessageRead
) {
}