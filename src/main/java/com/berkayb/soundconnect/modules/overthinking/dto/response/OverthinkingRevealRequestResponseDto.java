package com.berkayb.soundconnect.modules.overthinking.dto.response;

import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingRevealRequestStatus;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.UUID;

public record OverthinkingRevealRequestResponseDto(
		UUID id,
		
		UUID postId,
		String postTitle,
		
		UUID requesterId,
		String requesterUsername,
		String requesterAvatarUrl,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode requesterVisibilityMode,
		
		UUID authorId,
		
		OverthinkingRevealRequestStatus status,
		
		LocalDateTime createdAt
		
) {
	public OverthinkingRevealRequestResponseDto {
		if (requesterVisibilityMode != ListenerVisibilityMode.GHOST) {
			requesterVisibilityMode = null;
		}
	}

	/** Keeps source compatibility for call sites that do not project contextual identity. */
	public OverthinkingRevealRequestResponseDto(
			UUID id,
			UUID postId,
			String postTitle,
			UUID requesterId,
			String requesterUsername,
			UUID authorId,
			OverthinkingRevealRequestStatus status,
			LocalDateTime createdAt
	) {
		this(
				id,
				postId,
				postTitle,
				requesterId,
				requesterUsername,
				null,
				null,
				authorId,
				status,
				createdAt
		);
	}
}
