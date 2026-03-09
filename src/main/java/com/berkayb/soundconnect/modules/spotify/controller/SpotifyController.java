package com.berkayb.soundconnect.modules.spotify.controller;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyArtistTopTrackResponseDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackSearchResponseDto;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Spotify.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(BASE)
@Tag(name = "Spotify", description = "Spotify app-only entegrasyon endpointleri")
public class SpotifyController {
	
	private final SpotifyService spotifyService;
	
	// Track picker için: kullanıcı arar, listeden seçer.
	@GetMapping(SEARCH_TRACKS)
	public ResponseEntity<BaseResponse<SpotifyTrackSearchResponseDto>> searchTracks(
			@RequestParam String q,
			@RequestParam(defaultValue = "5") int limit
	) {
		var data = spotifyService.searchTracks(q, limit);
		return ResponseEntity.ok(BaseResponse.<SpotifyTrackSearchResponseDto>builder()
		                                     .success(true).message("Spotify tracks fetched").code(200).data(data).build());
	}
	
	// Seçilmiş track detayını almak için (profilde göstermek)
	@GetMapping(TRACK_BY_ID)
	public ResponseEntity<BaseResponse<SpotifyTrackItemDto>> getTrackById(@PathVariable String trackId) {
		var data = spotifyService.getTrackById(trackId);
		return ResponseEntity.ok(BaseResponse.<SpotifyTrackItemDto>builder()
		                                     .success(true).message("Spotify track fetched").code(200).data(data).build());
	}
}