package com.berkayb.soundconnect.modules.overthinking.dto.response;

import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingArtistType;

import java.util.UUID;

/**
 * Client'a dönen Overthinking post çıktısı.
 */
public record OverthinkingPostResponseDto(
		UUID id,
		UUID authorId,
		String title,
		String content,
		
		// muzik bilgisi
		String spotifyTrackUrl,
		String spotifyArtistId,
		UUID musicianTrackId,
		UUID bandTrackId,
		
		// eslesen sanatci
		UUID artistId,
		OverthinkingArtistType artistType
) {}