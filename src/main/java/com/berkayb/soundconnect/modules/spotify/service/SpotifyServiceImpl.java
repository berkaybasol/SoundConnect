package com.berkayb.soundconnect.modules.spotify.service;

import com.berkayb.soundconnect.modules.spotify.client.SpotifyApiClient;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackSearchResponseDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.modules.spotify.support.SpotifyPlaylistUrlParser;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SpotifyServiceImpl implements SpotifyService {
	
	private final SpotifyApiClient spotifyApiClient;
	
	@Override
	public SpotifyTrackSearchResponseDto searchTracks(String query, int limit) {
		
		if (query == null || query.isBlank()) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		
		String normalizedQuery = query.trim();
		
		if (normalizedQuery.length() > 100) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		
		int safeLimit = (limit <= 0) ? 5 : Math.min(limit, 10);
		var tracks = spotifyApiClient.searchTracks(normalizedQuery, safeLimit);
		
		return new SpotifyTrackSearchResponseDto(normalizedQuery, safeLimit, tracks);
	}
	
	@Override
	public SpotifyTrackItemDto getTrackById(String trackId) {
		if (trackId == null || trackId.isBlank()) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		return spotifyApiClient.getTrackById(trackId.trim());
	}

	@Override
	public List<SpotifyTrackItemDto> getTracksByIds(List<String> trackIds) {
		if (trackIds == null || trackIds.isEmpty() || trackIds.size() > 50
				|| trackIds.stream().anyMatch(id -> id == null || id.isBlank() || id.strip().length() > 64)) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		return spotifyApiClient.getTracksByIds(trackIds.stream().map(String::strip).distinct().toList());
	}

	@Override
	public SpotifyPlaylistMetadataDto getPlaylistMetadata(String spotifyPlaylistUrl) {
		var reference = SpotifyPlaylistUrlParser.parse(spotifyPlaylistUrl);
		return spotifyApiClient.getPlaylistMetadata(reference.playlistId());
	}

	@Override
	public List<SpotifyPlaylistMetadataDto> getPlaylistMetadataBatch(
			List<String> spotifyPlaylistUrls
	) {
		if (spotifyPlaylistUrls == null || spotifyPlaylistUrls.size() > 4) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		if (spotifyPlaylistUrls.isEmpty()) {
			return List.of();
		}
		List<String> playlistIds = spotifyPlaylistUrls.stream()
				.map(SpotifyPlaylistUrlParser::parse)
				.map(SpotifyPlaylistUrlParser.SpotifyPlaylistReference::playlistId)
				.toList();
		if (playlistIds.stream().distinct().count() != playlistIds.size()) {
			throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
		}
		return spotifyApiClient.getPlaylistMetadataBatch(playlistIds);
	}
}
