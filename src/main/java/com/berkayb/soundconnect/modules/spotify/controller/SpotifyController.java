package com.berkayb.soundconnect.modules.spotify.controller;

import com.berkayb.soundconnect.modules.spotify.dto.request.SpotifyTracksByIdsRequest;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackSearchResponseDto;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Spotify.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(BASE)
@Tag(name = "Spotify", description = "Spotify app-only entegrasyon endpointleri")
@Validated
public class SpotifyController {
	
	private final SpotifyService spotifyService;
	
	@PostMapping(TRACKS_BY_IDS)
	public ResponseEntity<BaseResponse<List<SpotifyTrackItemDto>>> getTracksByIds(
			@Valid @RequestBody SpotifyTracksByIdsRequest request) {
		var data = spotifyService.getTracksByIds(request.ids());
		return ResponseEntity.ok(BaseResponse.<List<SpotifyTrackItemDto>>builder()
		                                     .success(true).message("Spotify tracks fetched").code(200).data(data).build());
	}
	
	// Track picker için: kullanıcı arar, listeden seçer.
	@GetMapping(SEARCH_TRACKS)
	public ResponseEntity<BaseResponse<SpotifyTrackSearchResponseDto>> searchTracks(
			@RequestParam
			@NotBlank(message = "Query must not be blank")
			String q,
			
			@RequestParam(defaultValue = "5")
			@Min(value = 1, message = "Limit must be at least 1")
			@Max(value = 10, message = "Limit must be at most 10")
			int limit
	) {
		var data = spotifyService.searchTracks(q, limit);
		return ResponseEntity.ok(BaseResponse.<SpotifyTrackSearchResponseDto>builder()
		                                     .success(true).message("Spotify tracks fetched").code(200).data(data).build());
	}
	
	// Seçilmiş track detayını almak için (profilde göstermek)
	@GetMapping(TRACK_BY_ID)
	public ResponseEntity<BaseResponse<SpotifyTrackItemDto>> getTrackById(
			@PathVariable
			@NotBlank(message = "Track id must not be blank")
			String trackId
	) {
		var data = spotifyService.getTrackById(trackId);
		return ResponseEntity.ok(BaseResponse.<SpotifyTrackItemDto>builder()
		                                     .success(true).message("Spotify track fetched").code(200).data(data).build());
	}
}
