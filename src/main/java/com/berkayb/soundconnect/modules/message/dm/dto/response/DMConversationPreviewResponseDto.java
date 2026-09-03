package com.berkayb.soundconnect.modules.message.dm.dto.response;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

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
		Boolean lastMessageRead,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode otherUserVisibilityMode
) {
	public DMConversationPreviewResponseDto {
		otherUserVisibilityMode = ghostOnly(otherUserVisibilityMode);
	}

	public DMConversationPreviewResponseDto(
			UUID conversationId,
			UUID otherUserId,
			String otherUsername,
			String otherUserProfilePicture,
			String lastMessageContent,
			String lastMessageType,
			UUID lastMessageSenderId,
			LocalDateTime lastMessageAt,
			Boolean lastMessageRead
	) {
		this(
				conversationId,
				otherUserId,
				otherUsername,
				otherUserProfilePicture,
				lastMessageContent,
				lastMessageType,
				lastMessageSenderId,
				lastMessageAt,
				lastMessageRead,
				null
		);
	}

	private static ListenerVisibilityMode ghostOnly(ListenerVisibilityMode visibilityMode) {
		return visibilityMode == ListenerVisibilityMode.GHOST ? ListenerVisibilityMode.GHOST : null;
	}
}
