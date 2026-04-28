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
		String spotifyTrackUrl,
		String spotifyArtistId,
		
		// MusicianProfile’dan track
		UUID musicianTrackId,
		
		// Band track
		UUID bandTrackId
) {}