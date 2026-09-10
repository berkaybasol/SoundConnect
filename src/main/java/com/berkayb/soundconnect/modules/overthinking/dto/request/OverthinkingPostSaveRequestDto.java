package com.berkayb.soundconnect.modules.overthinking.dto.request;

import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingVisibilityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Overthinking post create/update isteği.
 * Müzik üç kaynaktan birinden gelir: Spotify / musicianTrack / bandTrack.
 * Sadece biri dolu olmalı.
 */
public record OverthinkingPostSaveRequestDto(
		@NotBlank
		@Size(max = 64)
		String title,
		
		@NotBlank
		@Size(max = 10240)
		String content,
		
		@NotNull OverthinkingVisibilityType visibilityType, // postun gorunur mu anonim mi paylasilacagini belirler
		
		// Spotify seçilirse
		@Size(max = 1024)
		String spotifyTrackUrl,
		@Size(max = 255)
		String spotifyArtistId,
		@Size(max = 512)
		String spotifyTrackName,
		@Size(max = 512)
		String spotifyArtistName,
		@Size(max = 1024)
		String spotifyAlbumImageUrl,
		
		// MusicianProfile’dan track
		UUID musicianTrackId,
		
		// Band track
		UUID bandTrackId,
		UUID clientRequestId
) {
    /** Compatibility for older clients and existing internal callers. New clients send a stable UUID per publication. */
    public OverthinkingPostSaveRequestDto(String title, String content, OverthinkingVisibilityType visibilityType,
            String spotifyTrackUrl, String spotifyArtistId, String spotifyTrackName, String spotifyArtistName,
            String spotifyAlbumImageUrl, UUID musicianTrackId, UUID bandTrackId) {
        this(title, content, visibilityType, spotifyTrackUrl, spotifyArtistId, spotifyTrackName, spotifyArtistName,
                spotifyAlbumImageUrl, musicianTrackId, bandTrackId, null);
    }
}
