package com.berkayb.soundconnect.modules.overthinking.dto.response;

import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingRevealRequestStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record OverthinkingRevealRequestResponseDto(
		UUID id,
		
		UUID postId,
		String postTitle,
		
		UUID requesterId,
		String requesterUsername,
		
		UUID authorId,
		
		OverthinkingRevealRequestStatus status,
		
		LocalDateTime createdAt
		
) {
}