package com.berkayb.soundconnect.modules.overthinking.mapper;

import com.berkayb.soundconnect.modules.overthinking.dto.response.OverthinkingPostResponseDto;
import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.mapstruct.Mapper;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Mapper(componentModel = "spring")
public abstract class OverthinkingPostMapper {
	
	@Autowired
	protected PublicProfileResolverService profileResolverService;
	
	public OverthinkingPostResponseDto toDto(
			OverthinkingPost post,
			boolean canViewAuthor,
			long likeCount,
			long commentCount,
			boolean likedByMe,
			SpotifyTrackItemDto spotifyTrack
	) {
		User author = post.getAuthor();
		
		return new OverthinkingPostResponseDto(
				post.getId(),
				
				canViewAuthor ? author.getId() : null,
				canViewAuthor ? author.getUsername() : "Anonymous",
				canViewAuthor ? resolveAuthorAvatar(author) : null,
				
				post.isAnonymous(),
				canViewAuthor,
				post.getVisibilityType(),
				
				post.getTitle(),
				post.getContent(),
				
				post.getSpotifyTrackUrl(),
				post.getSpotifyArtistId(),
				firstText(post.getSpotifyTrackName(), spotifyTrack != null ? spotifyTrack.name() : null),
				firstText(post.getSpotifyArtistName(), spotifyTrack != null ? artistName(spotifyTrack.artistNames()) : null),
				firstText(post.getSpotifyAlbumImageUrl(), spotifyTrack != null ? spotifyTrack.albumImageUrl() : null),
				post.getMusicianTrackId(),
				post.getBandTrackId(),
				
				post.getArtistId(),
				post.getArtistType(),
				
				likeCount,
				commentCount,
				likedByMe
		);
	}
	
	private String resolveAuthorAvatar(User author) {
		if (author == null) {
			return null;
		}
		
		try {
			var resolved = profileResolverService.resolveByUserId(author.getId());
			if (resolved != null && resolved.profiles() != null) {
				for (UserProfileTargetDto profile : resolved.profiles()) {
					if (hasText(profile.profilePictureUrl())) {
						return profile.profilePictureUrl();
					}
				}
			}
		} catch (Exception ignored) {
		}
		
		return hasText(author.getProfilePicture()) ? author.getProfilePicture() : null;
	}
	
	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
	
	private String artistName(List<String> artistNames) {
		if (artistNames == null || artistNames.isEmpty()) {
			return null;
		}
		String joined = String.join(", ", artistNames).trim();
		return joined.isBlank() ? null : joined;
	}
	
	private String firstText(String primary, String fallback) {
		if (hasText(primary)) {
			return primary.trim();
		}
		return hasText(fallback) ? fallback.trim() : null;
	}
}
