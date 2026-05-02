package com.berkayb.soundconnect.modules.overthinking.dto.response;

import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingArtistType;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;

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
		boolean likedByMe
) {}
