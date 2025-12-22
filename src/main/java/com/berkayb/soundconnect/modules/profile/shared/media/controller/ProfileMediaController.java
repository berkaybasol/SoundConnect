package com.berkayb.soundconnect.modules.profile.shared.media.controller;

import com.berkayb.soundconnect.modules.profile.shared.media.dto.response.ProfileMediaUiResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.media.service.ProfileMediaUiService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/profiles")
public class ProfileMediaController {
	
	private final ProfileMediaUiService profileMediaUiService;
	
	@GetMapping("/{profileType}/{profileId}/media")
	@Operation(description = "profile ekrani icin gerekli tum medya icerigini getir")
	public BaseResponse<ProfileMediaUiResponseDto> getProfileMedia(
			@PathVariable ProfileType profileType,
			@PathVariable UUID profileId
	) {
		ProfileMediaUiResponseDto response = profileMediaUiService.getProfileMedia(profileType, profileId);
		
		return BaseResponse.<ProfileMediaUiResponseDto>builder()
				.success(true)
				.code(200)
				.message("profile media loaded.")
				.data(response)
				.build();
	}
}