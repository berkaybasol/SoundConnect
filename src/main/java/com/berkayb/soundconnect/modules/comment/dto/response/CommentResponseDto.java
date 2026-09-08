package com.berkayb.soundconnect.modules.comment.dto.response;

import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.UUID;

public record CommentResponseDto(
		UUID id,
		UserSummaryDto user,
		boolean anonymousAuthor,
		String text,
		boolean deleted,
		UUID parentCommentId,
		int replyCount,
		@JsonFormat(shape = JsonFormat.Shape.STRING, timezone = "UTC")
		Instant createdAt,
		long likeCount,
		boolean likedByMe
) {
	public CommentResponseDto(UUID id,UserSummaryDto user,boolean anonymousAuthor,String text,boolean deleted,
	                          UUID parentCommentId,int replyCount,Instant createdAt) {
		this(id,user,anonymousAuthor,text,deleted,parentCommentId,replyCount,createdAt,0,false);
	}
}
