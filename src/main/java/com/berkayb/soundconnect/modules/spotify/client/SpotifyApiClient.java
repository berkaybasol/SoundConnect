package com.berkayb.soundconnect.modules.spotify.client;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;

import java.util.List;

public interface SpotifyApiClient {
	// Track armaa: track picker ekrani buna dayanacak
	List<SpotifyTrackItemDto> searchTracks(String query, int limit);
	
	// Track detayi. profilde secilen trackIdleri gostermek icin
	SpotifyTrackItemDto getTrackById(String trackId);
	
	List<SpotifyTrackItemDto> getTracksByIds(List<String> ids);

	SpotifyPlaylistMetadataDto getPlaylistMetadata(String spotifyPlaylistId);

	List<SpotifyPlaylistMetadataDto> getPlaylistMetadataBatch(List<String> spotifyPlaylistIds);
}
