package com.berkayb.soundconnect.modules.comment.dto.response;

import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

public record CommentReplyResponseDto(
		UUID id,
		UserSummaryDto user,
		boolean anonymousAuthor,
		String text,
		boolean deleted,
		UUID parentCommentId,
		@JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
		Instant createdAt,
		long likeCount,
		boolean likedByMe
) {
	public CommentReplyResponseDto(UUID id,UserSummaryDto user,boolean anonymousAuthor,String text,boolean deleted,
	                               UUID parentCommentId,Instant createdAt) {
		this(id,user,anonymousAuthor,text,deleted,parentCommentId,createdAt,0,false);
	}
}
