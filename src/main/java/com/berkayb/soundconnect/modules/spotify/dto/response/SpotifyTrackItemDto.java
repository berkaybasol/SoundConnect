package com.berkayb.soundconnect.modules.spotify.dto.response;

import java.util.List;

public record SpotifyTrackItemDto(
		String spotifyTrackId,
		String name,
		Integer durationMs,
		boolean explicit,
		String previewUrl, // 30 sn preview icin. yoksa null
		String spotifyUrl, // tamamini dinle kismi icin
		String albumName,
		String albumImageUrl, // UI karti icin ama kullanmicaz bizim ui da gorsel yok
		List<String> artistNames
) {
}