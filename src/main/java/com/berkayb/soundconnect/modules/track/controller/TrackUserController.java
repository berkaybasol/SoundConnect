package com.berkayb.soundconnect.modules.track.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.track.dto.request.TrackCreateRequestDto;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import com.berkayb.soundconnect.shared.constant.EndPoints.Track;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping(Track.BASE)
@Tag(name = "FOR USERS / Tracks", description = "Müzisyen profillerine ait track işlemleri")
public class TrackUserController {
	
	private final TrackService trackService;
	
	@Operation(summary = "Yeni track oluşturur")
	@PostMapping(Track.CREATE)
	public ResponseEntity<BaseResponse<TrackResponseDto>> createTrack(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID profileId,
			@RequestBody TrackCreateRequestDto dto
	) {
		var created = trackService.createTrack(
				userDetails.getUser().getId(),
				profileId,
				dto
		);
		
		return ResponseEntity.ok(BaseResponse.<TrackResponseDto>builder()
		                                     .success(true)
		                                     .code(201)
		                                     .message("Track oluşturuldu")
		                                     .data(created)
		                                     .build());
	}
	
	@Operation(summary = "Profildeki tüm trackleri getirir")
	@GetMapping(Track.LIST)
	public ResponseEntity<BaseResponse<List<TrackResponseDto>>> getTracks(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID profileId
	) {
		var list = trackService.getTracksByProfile(
				userDetails.getUser().getId(),
				profileId
		);
		
		return ResponseEntity.ok(BaseResponse.<List<TrackResponseDto>>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Track listesi getirildi")
		                                     .data(list)
		                                     .build());
	}
	
	@Operation(summary = "Tekil track getirir")
	@GetMapping(Track.BY_ID)
	public ResponseEntity<BaseResponse<TrackResponseDto>> getTrackById(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID profileId,
			@PathVariable UUID trackId
	) {
		var dto = trackService.getTrack(
				userDetails.getUser().getId(),
				profileId,
				trackId
		);
		
		return ResponseEntity.ok(BaseResponse.<TrackResponseDto>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Track getirildi")
		                                     .data(dto)
		                                     .build());
	}
	
	@Operation(summary = "Track siler")
	@DeleteMapping(Track.DELETE)
	public ResponseEntity<BaseResponse<Void>> deleteTrack(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID profileId,
			@PathVariable UUID trackId
	) {
		trackService.deleteTrack(
				userDetails.getUser().getId(),
				profileId,
				trackId
		);
		
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Track silindi")
		                                     .build());
	}
}