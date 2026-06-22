package com.berkayb.soundconnect.modules.track.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.track.dto.request.TrackCreateRequestDto;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(EndPoints.Track.BASE)
@RequiredArgsConstructor
@Slf4j
public class MusicianTrackController {
	
	private final TrackService trackService;
	
	private UUID getUserId(Object principal) {
		if (principal instanceof UsernamePasswordAuthenticationToken auth &&
				auth.getPrincipal() instanceof UserDetailsImpl user) {
			return user.getUser().getId();
		}
		return null;
	}
	
	// ===============================
	// CREATE TRACK
	// ===============================
	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping(EndPoints.Track.CREATE)
	@Operation(summary = "Müzisyen profiline ait yeni bir track oluştur")
	public ResponseEntity<BaseResponse<TrackResponseDto>> createTrack(
			UsernamePasswordAuthenticationToken principal,
			@PathVariable("profileId") UUID profileId,
			@RequestBody TrackCreateRequestDto dto
	) {
		
		UUID userId = getUserId(principal);
		
		log.info("[Track] Creating track for musicianProfileId={} by userId={}", profileId, userId);
		
		TrackResponseDto result =
				trackService.createTrack(profileId, userId, dto);
		
		return ResponseEntity.ok(
				BaseResponse.<TrackResponseDto>builder()
				            .success(true)
				            .message("Track başarıyla oluşturuldu")
				            .code(200)
				            .data(result)
				            .build()
		);
	}
	
	// ===============================
	// LIST TRACKS
	// ===============================
	@GetMapping(EndPoints.Track.LIST)
	@Operation(summary = "Müzisyen profiline ait tüm trackleri getir")
	public ResponseEntity<BaseResponse<Page<TrackResponseDto>>> listTracks(
			@PathVariable("profileId") UUID profileId,
			Pageable pageable
	) {
		Page<TrackResponseDto> tracks =
				trackService.listTracks(profileId, TrackOwnerType.MUSICIAN_PROFILE, pageable);
		
		return ResponseEntity.ok(
				BaseResponse.<Page<TrackResponseDto>>builder()
				            .success(true)
				            .message("Track listesi başarıyla getirildi")
				            .code(200)
				            .data(tracks)
				            .build()
		);
	}
	
	// ===============================
	// GET BY ID
	// ===============================
	@GetMapping(EndPoints.Track.BY_ID)
	@Operation(summary = "Musician profile track detayını getir")
	public ResponseEntity<BaseResponse<TrackResponseDto>> getTrack(
			@PathVariable("trackId") UUID trackId
	) {
		TrackResponseDto dto = trackService.getTrackById(trackId);
		
		return ResponseEntity.ok(
				BaseResponse.<TrackResponseDto>builder()
				            .success(true)
				            .message("Track başarıyla getirildi")
				            .code(200)
				            .data(dto)
				            .build()
		);
	}
	
	// ===============================
	// DELETE (Owner authorization service içinde kontrol edilir)
	// ===============================
	@PreAuthorize("hasRole('MUSICIAN')")
	@DeleteMapping(EndPoints.Track.DELETE)
	@Operation(summary = "Track sil")
	public ResponseEntity<BaseResponse<Void>> deleteTrack(
			UsernamePasswordAuthenticationToken principal,
			@PathVariable("profileId") UUID profileId,
			@PathVariable("trackId") UUID trackId
	) {
		UUID userId = getUserId(principal);
		log.info("[Track] Deleting trackId={} for musicianProfileId={} by userId={}",
		         trackId, profileId, userId);
		
		trackService.deleteTrack(trackId, profileId, userId, TrackOwnerType.MUSICIAN_PROFILE);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .message("Track başarıyla silindi")
				            .code(200)
				            .build()
		);
	}
}
