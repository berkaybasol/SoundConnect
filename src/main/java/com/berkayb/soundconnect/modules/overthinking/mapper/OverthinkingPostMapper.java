package com.berkayb.soundconnect.modules.overthinking.mapper;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface OverthinkingPostMapper {
	
	default OverthinkingPostResponseDto toDto(
			OverthinkingPost post,
			boolean canViewAuthor,
			long likeCount,
			long commentCount,
			boolean likedByMe
	) {
		User author = post.getAuthor();
		
		return new OverthinkingPostResponseDto(
				post.getId(),
				
				canViewAuthor ? author.getId() : null,
				canViewAuthor ? author.getUsername() : "Anonymous",
				canViewAuthor ? author.getProfilePicture() : null,
				
				post.isAnonymous(),
				canViewAuthor,
				post.getVisibilityType(),
				
				post.getTitle(),
				post.getContent(),
				
				post.getSpotifyTrackUrl(),
				post.getSpotifyArtistId(),
				post.getMusicianTrackId(),
				post.getBandTrackId(),
				
				post.getArtistId(),
				post.getArtistType(),
				
				likeCount,
				commentCount,
				likedByMe
		);
	}
}