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
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(EndPoints.BandTrack.BASE)
@RequiredArgsConstructor
@Slf4j
public class BandTrackController {
	
	private final TrackService trackService;
	
	private UUID getUserId(Object principal) {
		if (principal instanceof UsernamePasswordAuthenticationToken auth &&
				auth.getPrincipal() instanceof UserDetailsImpl user) {
			return user.getUser().getId();
		}
		return null;
	}
	
	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping(EndPoints.BandTrack.CREATE)
	@Operation(summary = "Band için yeni track oluştur")
	public ResponseEntity<BaseResponse<TrackResponseDto>> createBandTrack(
			UsernamePasswordAuthenticationToken principal,
			@PathVariable("bandId") UUID bandId,
			@RequestBody TrackCreateRequestDto dto
	) {
		UUID userId = getUserId(principal);
		
		log.info("[Track] Creating band track for bandId={} by userId={}", bandId, userId);
		
		TrackResponseDto result =
				trackService.createTrack(bandId, userId, dto);
		
		return ResponseEntity.ok(
				BaseResponse.<TrackResponseDto>builder()
				            .success(true)
				            .message("Band track başarıyla oluşturuldu")
				            .code(200)
				            .data(result)
				            .build()
		);
	}
	
	@GetMapping(EndPoints.BandTrack.LIST)
	@Operation(summary = "Band'a ait tüm trackleri getir")
	public ResponseEntity<BaseResponse<Page<TrackResponseDto>>> listBandTracks(
			@PathVariable("bandId") UUID bandId,
			Pageable pageable
	) {
		Page<TrackResponseDto> tracks =
				trackService.listTracks(bandId, TrackOwnerType.BAND, pageable);
		
		return ResponseEntity.ok(
				BaseResponse.<Page<TrackResponseDto>>builder()
				            .success(true)
				            .message("Band track listesi başarıyla getirildi")
				            .code(200)
				            .data(tracks)
				            .build()
		);
	}
	
	@GetMapping(EndPoints.BandTrack.BY_ID)
	@Operation(summary = "Band track detayını getir")
	public ResponseEntity<BaseResponse<TrackResponseDto>> getBandTrack(
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
	
	@PreAuthorize("hasRole('MUSICIAN')")
	@DeleteMapping(EndPoints.BandTrack.DELETE)
	@Operation(summary = "Band track sil")
	public ResponseEntity<BaseResponse<Void>> deleteBandTrack(
			UsernamePasswordAuthenticationToken principal,
			@PathVariable("bandId") UUID bandId,
			@PathVariable("trackId") UUID trackId
	) {
		UUID userId = getUserId(principal);
		
		log.info("[Track] Deleting band trackId={} bandId={} by userId={}",
		         trackId, bandId, userId);
		
		trackService.deleteTrack(trackId, bandId, userId, TrackOwnerType.BAND);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .message("Band track başarıyla silindi")
				            .code(200)
				            .build()
		);
	}
}
