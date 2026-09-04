package com.berkayb.soundconnect.modules.spotify.service;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackSearchResponseDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;

import java.util.List;


public interface SpotifyService {
	
	SpotifyTrackSearchResponseDto searchTracks(String query, int limit);
	SpotifyTrackItemDto getTrackById(String trackId);
	List<SpotifyTrackItemDto> getTracksByIds(List<String> trackIds);
	SpotifyPlaylistMetadataDto getPlaylistMetadata(String spotifyPlaylistUrl);
	List<SpotifyPlaylistMetadataDto> getPlaylistMetadataBatch(List<String> spotifyPlaylistUrls);
	
}
