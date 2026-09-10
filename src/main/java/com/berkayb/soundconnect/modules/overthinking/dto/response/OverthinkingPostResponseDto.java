package com.berkayb.soundconnect.modules.overthinking.dto.response;

import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingArtistType;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Client'a dönen Overthinking post çıktısı.
 * Author bilgisi anonimlik kurallarına göre maskelenebilir.
 */
public record OverthinkingPostResponseDto(
		UUID id,
		
		// author bilgisi sadece viewer gorme hakkina sahipse dolar
		UUID authorId,
		String authorUsername,
		String authorAvatarUrl,
		@JsonInclude(JsonInclude.Include.NON_NULL)
		ListenerVisibilityMode authorVisibilityMode,
		
		boolean anonymous,
		boolean canViewAuthor,
		OverthinkingVisibilityType visibilityType,
		
		String title,
		String content,
		
		// muzik bilgisi
		String spotifyTrackUrl,
		String spotifyArtistId,
		String spotifyTrackName,
		String spotifyArtistName,
		String spotifyAlbumImageUrl,
		UUID musicianTrackId,
		UUID bandTrackId,
		
		// eslesen sanatci
		UUID artistId,
		OverthinkingArtistType artistType,
		
		// engagement
		long likeCount,
		long commentCount,
		boolean likedByMe,
		boolean revealRequestPending
) {
	public OverthinkingPostResponseDto {
		revealRequestPending = anonymous && !canViewAuthor && revealRequestPending;
		if (!canViewAuthor || authorId == null) {
			authorId = null;
			authorUsername = "Anonymous";
			authorAvatarUrl = null;
			authorVisibilityMode = null;
		} else if (authorVisibilityMode != ListenerVisibilityMode.GHOST) {
			authorVisibilityMode = null;
		}
	}

	public OverthinkingPostResponseDto(
			UUID id, UUID authorId, String authorUsername, String authorAvatarUrl,
			ListenerVisibilityMode authorVisibilityMode, boolean anonymous, boolean canViewAuthor,
			OverthinkingVisibilityType visibilityType, String title, String content,
			String spotifyTrackUrl, String spotifyArtistId, String spotifyTrackName,
			String spotifyArtistName, String spotifyAlbumImageUrl, UUID musicianTrackId, UUID bandTrackId,
			UUID artistId, OverthinkingArtistType artistType, long likeCount, long commentCount, boolean likedByMe
	) {
		this(id, authorId, authorUsername, authorAvatarUrl, authorVisibilityMode, anonymous, canViewAuthor,
				visibilityType, title, content, spotifyTrackUrl, spotifyArtistId, spotifyTrackName, spotifyArtistName,
				spotifyAlbumImageUrl, musicianTrackId, bandTrackId, artistId, artistType, likeCount, commentCount,
				likedByMe, false);
	}

	public OverthinkingPostResponseDto withRevealRequestPending(boolean pending) {
		return new OverthinkingPostResponseDto(id, authorId, authorUsername, authorAvatarUrl, authorVisibilityMode,
				anonymous, canViewAuthor, visibilityType, title, content, spotifyTrackUrl, spotifyArtistId,
				spotifyTrackName, spotifyArtistName, spotifyAlbumImageUrl, musicianTrackId, bandTrackId,
				artistId, artistType, likeCount, commentCount, likedByMe, pending);
	}

	/** Keeps source compatibility for existing service and test call sites. */
	public OverthinkingPostResponseDto(
			UUID id,
			UUID authorId,
			String authorUsername,
			String authorAvatarUrl,
			boolean anonymous,
			boolean canViewAuthor,
			OverthinkingVisibilityType visibilityType,
			String title,
			String content,
			String spotifyTrackUrl,
			String spotifyArtistId,
			String spotifyTrackName,
			String spotifyArtistName,
			String spotifyAlbumImageUrl,
			UUID musicianTrackId,
			UUID bandTrackId,
			UUID artistId,
			OverthinkingArtistType artistType,
			long likeCount,
			long commentCount,
			boolean likedByMe
	) {
		this(
				id,
				authorId,
				authorUsername,
				authorAvatarUrl,
				null,
				anonymous,
				canViewAuthor,
				visibilityType,
				title,
				content,
				spotifyTrackUrl,
				spotifyArtistId,
				spotifyTrackName,
				spotifyArtistName,
				spotifyAlbumImageUrl,
				musicianTrackId,
				bandTrackId,
				artistId,
				artistType,
				likeCount,
				commentCount,
				likedByMe
		);
	}
}
