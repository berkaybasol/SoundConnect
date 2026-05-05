package com.berkayb.soundconnect.modules.profile.shared.media.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.shared.media.dto.request.ProfileMediaAddRequestDto;
import com.berkayb.soundconnect.modules.profile.shared.media.service.ProfileMediaService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/profile-media")
public class ProfileMediaManagementController {
	
	private final ProfileMediaService profileMediaService;
	
	@PostMapping
	@Operation(summary = "Profile medya ekle")
	public BaseResponse<Void> addMedia(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestBody ProfileMediaAddRequestDto dto
	) {
		profileMediaService.addMedia(
				currentUserId(userDetails),
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

	@DeleteMapping("/{profileMediaId}")
	@Operation(summary = "Profile medya sil")
	public BaseResponse<Void> removeMedia(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID profileMediaId
	) {
		profileMediaService.removeMedia(currentUserId(userDetails), profileMediaId);
		return BaseResponse.<Void>builder()
				.success(true)
				.code(200)
				.message("Profile media silindi")
				.build();
	}

	private UUID currentUserId(UserDetailsImpl userDetails) {
		return userDetails.getUser().getId();
	}
}
