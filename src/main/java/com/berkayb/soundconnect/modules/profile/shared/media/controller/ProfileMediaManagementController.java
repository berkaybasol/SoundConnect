package com.berkayb.soundconnect.modules.profile.shared.media.controller;

import com.berkayb.soundconnect.modules.profile.shared.media.dto.request.ProfileMediaAddRequestDto;
import com.berkayb.soundconnect.modules.profile.shared.media.service.ProfileMediaService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/profile-media")
public class ProfileMediaManagementController {
	
	private final ProfileMediaService profileMediaService;
	
	@PostMapping
	@Operation(summary = "Profile medya ekle")
	public BaseResponse<Void> addMedia(@RequestBody ProfileMediaAddRequestDto dto
	) {
		profileMediaService.addMedia(
				dto.profileType(),
				dto.profileId(),
				dto.mediaAssetId(),
				dto.role(),
				dto.orderIndex()
		);
		
		return BaseResponse.<Void>builder()
				.success(true)
				.code(200)
				.message("Media profile eklendi")
				.build();
	}
}