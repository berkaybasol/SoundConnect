package com.berkayb.soundconnect.modules.spotify.service;

import com.berkayb.soundconnect.modules.spotify.client.SpotifyApiClient;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyArtistTopTrackResponseDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackSearchResponseDto;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SpotifyServiceImpl implements SpotifyService {
	
	private final SpotifyApiClient spotifyApiClient;
	
	@Override
	public SpotifyTrackSearchResponseDto searchTracks(String query, int limit) {
		
		if (query == null || query.isBlank()) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		
		int safeLimit = (limit <= 0) ? 5 : Math.min(limit, 10);
		var tracks = spotifyApiClient.searchTracks(query.trim(), safeLimit);
		
		return new SpotifyTrackSearchResponseDto(query.trim(), safeLimit, tracks);
	}
	
	@Override
	public SpotifyTrackItemDto getTrackById(String trackId) {
		if (trackId == null || trackId.isBlank()) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		return spotifyApiClient.getTrackById(trackId.trim());
	}
}