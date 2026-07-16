package com.berkayb.soundconnect.modules.profile.StudioProfile.controller.publicapi;

import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.StudioProfile.PUBLIC_BASE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.StudioProfile.PUBLIC_BY_PROFILE_ID;

@RestController
@RequestMapping(PUBLIC_BASE)
@RequiredArgsConstructor
@Tag(name = "PUBLIC / Studio Profile", description = "Public studio profile endpoints")
public class StudioProfilePublicController {
	private final StudioProfileService studioProfileService;
	
	@GetMapping(PUBLIC_BY_PROFILE_ID)
	public ResponseEntity<BaseResponse<StudioProfileResponseDto>> getProfileByProfileId(
			@PathVariable UUID profileId
	) {
		StudioProfileResponseDto dto = studioProfileService.getProfileByProfileId(profileId);
		return ResponseEntity.ok(
				BaseResponse.<StudioProfileResponseDto>builder()
				            .success(true)
				            .code(200)
				            .message("Studio public profile fetched")
				            .data(dto)
				            .build()
		);
	}
}
