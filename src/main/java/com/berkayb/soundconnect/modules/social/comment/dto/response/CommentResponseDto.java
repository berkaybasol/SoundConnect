package com.berkayb.soundconnect.modules.social.comment.dto.response;

import com.berkayb.soundconnect.modules.social.comment.dto.support.UserSummaryDto;

import java.util.UUID;

public record CommentResponseDto(
		UUID id,
		UserSummaryDto user,
		String text,
		boolean deleted,
		BURDASIN!!!!!!!!!!!!!!!!
) {
}