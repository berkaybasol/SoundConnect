package com.berkayb.soundconnect.modules.profile.StudioProfile.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.berkayb.soundconnect.shared.constant.EndPoints.StudioProfile.*;

@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR USERS / Studio Profile", description = "User kendi stüdyo profilini yönetir")
public class StudioProfileUserController {
	private final StudioProfileService studioProfileService;
	
	
	@GetMapping(ME)
	public ResponseEntity<BaseResponse<StudioProfileResponseDto>> getMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails) {  // @AuthenticationPrincipal: Authenticated olmus kullanicinin
																	// bilgilerini direkt olarak buraya enjekte ediyoruz.
		var dto = studioProfileService.getProfileByUserId(userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<StudioProfileResponseDto>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Profil getirildi")
				                         .data(dto)
				                         .build());
	}
	@PutMapping(UPDATE)
	public ResponseEntity<BaseResponse<StudioProfileResponseDto>> updateProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestBody StudioProfileSaveRequestDto dto) {
		var updated = studioProfileService.updateProfile(userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<StudioProfileResponseDto>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Profil guncellendi")
				                         .data(updated)
				                         .build());
	}
	
}