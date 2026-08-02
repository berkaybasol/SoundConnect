package com.berkayb.soundconnect.modules.track.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.track.dto.request.TrackCreateRequestDto;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(EndPoints.StudioTrack.BASE)
@RequiredArgsConstructor
public class StudioTrackController {
	private final TrackService trackService;

	@PostMapping
	@PreAuthorize("hasRole('STUDIO')")
	public ResponseEntity<BaseResponse<TrackResponseDto>> create(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID studioProfileId,
			@Valid @RequestBody TrackCreateRequestDto request
	) {
		TrackResponseDto data = trackService.createTrack(
				studioProfileId,
				principal.getId(),
				request
		);
		return ResponseEntity.ok(BaseResponse.<TrackResponseDto>builder()
				.success(true).code(200).message("Studio track created").data(data).build());
	}

	@GetMapping
	public ResponseEntity<BaseResponse<Page<TrackResponseDto>>> list(
			@PathVariable UUID studioProfileId,
			Pageable pageable
	) {
		Page<TrackResponseDto> data = trackService.listTracks(
				studioProfileId,
				TrackOwnerType.STUDIO_PROFILE,
				pageable
		);
		return ResponseEntity.ok(BaseResponse.<Page<TrackResponseDto>>builder()
				.success(true).code(200).message("Studio tracks fetched").data(data).build());
	}

	@DeleteMapping(EndPoints.StudioTrack.BY_ID)
	@PreAuthorize("hasRole('STUDIO')")
	public ResponseEntity<BaseResponse<Void>> delete(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID studioProfileId,
			@PathVariable UUID trackId
	) {
		trackService.deleteTrack(
				trackId,
				studioProfileId,
				principal.getId(),
				TrackOwnerType.STUDIO_PROFILE
		);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
				.success(true).code(200).message("Studio track deleted").build());
	}
}
