package com.berkayb.soundconnect.modules.overthinking.mapper;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingRevealRequestResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.comment.dto.support.UserSummaryDto;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OverthinkingRevealRequestMapper {
	
	@Mapping(target = "postId", expression = "java(request.getPost().getId())")
	@Mapping(target = "postTitle", expression = "java(request.getPost().getTitle())")
	@Mapping(target = "requesterId", expression = "java(request.getRequester().getId())")
	@Mapping(target = "requesterUsername", expression = "java(request.getRequester().getUsername())")
	@Mapping(target = "requesterAvatarUrl", ignore = true)
	@Mapping(target = "requesterVisibilityMode", ignore = true)
	@Mapping(target = "authorId", expression = "java(request.isApproved() ? request.getAuthor().getId() : null)")
	OverthinkingRevealRequestResponseDto toDto(OverthinkingRevealRequest request);

	default OverthinkingRevealRequestResponseDto toDto(
			OverthinkingRevealRequest request,
			GhostListenerIdentity ghostIdentity
	) {
		return toDto(request, ghostIdentity, null);
	}

	default OverthinkingRevealRequestResponseDto toDto(
			OverthinkingRevealRequest request,
			GhostListenerIdentity ghostIdentity,
			UserSummaryDto standardIdentity
	) {
		OverthinkingRevealRequestResponseDto base = toDto(request);
		if (ghostIdentity == null && standardIdentity == null) {
			return base;
		}
		return new OverthinkingRevealRequestResponseDto(
				base.id(),
				base.postId(),
				base.postTitle(),
				base.requesterId(),
				ghostIdentity != null ? ghostIdentity.username() : standardIdentity.username(),
				ghostIdentity != null ? ghostIdentity.profilePictureUrl() : standardIdentity.avatarUrl(),
				ghostIdentity != null ? ghostIdentity.visibilityMode() : standardIdentity.visibilityMode(),
				base.authorId(),
				base.status(),
				base.createdAt()
		);
	}
}
