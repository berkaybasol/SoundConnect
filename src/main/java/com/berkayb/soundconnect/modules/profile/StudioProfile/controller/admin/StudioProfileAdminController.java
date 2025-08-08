package com.berkayb.soundconnect.modules.profile.StudioProfile.controller.admin;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.StudioProfile.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMIN / Studio Profile", description = "Admin tüm kullanıcıların stüdyo profillerini yönetir")
public class StudioProfileAdminController {
	private final StudioProfileService studioProfileService;
	
	@GetMapping(BY_USER_ID)
	public ResponseEntity<BaseResponse<StudioProfileResponseDto>> getStudioProfileByUserId(
			@PathVariable UUID userId) {
		var dto = studioProfileService.getProfileByUserId(userId);
		return ResponseEntity.ok(BaseResponse.<StudioProfileResponseDto>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Profil getirildi")
				                         .data(dto)
				                         .build());
	}
	
	@PutMapping(ADMIN_UPDATE)
	public ResponseEntity<BaseResponse<StudioProfileResponseDto>> updateStudioProfileByUserId(
			@PathVariable UUID userId,
			@RequestBody StudioProfileSaveRequestDto dto) {
		var updated = studioProfileService.updateProfile(userId, dto);
		return ResponseEntity.ok(
				BaseResponse.<StudioProfileResponseDto>builder()
						.success(true)
						.code(200)
						.message("Profil guncellendi.")
						.data(updated)
						.build()
		);
	}
}