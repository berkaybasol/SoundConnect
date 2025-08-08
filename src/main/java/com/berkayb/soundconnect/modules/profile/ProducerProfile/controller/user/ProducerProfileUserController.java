package com.berkayb.soundconnect.modules.profile.ProducerProfile.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.request.ProducerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.response.ProducerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.service.ProducerProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.berkayb.soundconnect.shared.constant.EndPoints.ProducerProfile.*;

@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR USERS / Producer Profile", description = "User kendi producer profilini yönetir")
public class ProducerProfileUserController {
	private final ProducerProfileService producerProfileService;
	
	@GetMapping(ME)
	public ResponseEntity<BaseResponse<ProducerProfileResponseDto>> getMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails) {
		var dto = producerProfileService.getProfileByUserId(userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<ProducerProfileResponseDto>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Profile getirildi.")
				                         .data(dto)
				                         .build());
	}
	
	@PutMapping(UPDATE)
	public ResponseEntity<BaseResponse<ProducerProfileResponseDto>> updateProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestBody ProducerProfileSaveRequestDto dto) {
		var updated = producerProfileService.updateProfile(userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<ProducerProfileResponseDto>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Profil guncellendi")
				                         .data(updated)
				                         .build());
	}
}