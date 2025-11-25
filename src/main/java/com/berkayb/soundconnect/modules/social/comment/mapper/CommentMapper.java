package com.berkayb.soundconnect.modules.social.comment.mapper;

import com.berkayb.soundconnect.modules.social.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.social.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.modules.social.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.social.comment.entity.Comment;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Comment entity <-> DTO dönüşümleri için MapStruct mapper.
 */
@Mapper(componentModel = "spring")
public interface CommentMapper {
	
	/**
	 * User entity'den UI'da kullanılacak minimal user özet DTO'su.
	 */
	@Mapping(target = "id", source = "id")
	@Mapping(target = "username", source = "username")
	@Mapping(target = "avatarUrl", source = "profilePicture") // User'da profil resmi alanın adı buysa
	UserSummaryDto toUserSummaryDto(User user);
	
	/**
	 * Root comment için Response DTO.
	 * replyCount servisten parametre olarak gelir.
	 */
	@Mapping(target = "user", expression = "java(toUserSummaryDto(comment.getUser()))")
	@Mapping(target = "parentCommentId",
			expression = "java(comment.getParentComment() != null ? comment.getParentComment().getId() : null)")
	@Mapping(target = "replyCount", source = "replyCount")
	CommentResponseDto toCommentResponseDto(Comment comment, int replyCount);
	
	/**
	 * Reply (cevap) yorumlar için Response DTO.
	 * replyCount içermiyor; sadece temel bilgiler.
	 */
	@Mapping(target = "user", expression = "java(toUserSummaryDto(comment.getUser()))")
	@Mapping(target = "parentCommentId",
			expression = "java(comment.getParentComment() != null ? comment.getParentComment().getId() : null)")
	CommentReplyResponseDto toCommentReplyResponseDto(Comment comment);
}