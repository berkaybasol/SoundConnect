package com.berkayb.soundconnect.modules.profile.OrganizerProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.request.OrganizerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.response.OrganizerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.service.OrganizerProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.OrganizerProfile.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMIN / Organizer Profile", description = "")
public class OrganizerProfileAdminController {
	private final OrganizerProfileService organizerProfileService;
	
	@GetMapping(BY_USER_ID)
	public ResponseEntity<BaseResponse<OrganizerProfileResponseDto>> getOrganizerProfileByUserId(
			@PathVariable UUID userId) {
		var dto = organizerProfileService.getProfileByUserId(userId);
		return ResponseEntity.ok(BaseResponse.<OrganizerProfileResponseDto>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Profil getirildi")
				                         .data(dto)
				                         .build());
	}
	
	@PutMapping(ADMIN_UPDATE)
	public ResponseEntity<BaseResponse<OrganizerProfileResponseDto>> updateOrganizerProfileByUserId(
			@PathVariable UUID userId, @RequestBody OrganizerProfileSaveRequestDto dto) {
		var updated = organizerProfileService.updateProfile(userId, dto);
		return ResponseEntity.ok(
				BaseResponse.<OrganizerProfileResponseDto>builder()
						.success(true)
						.code(200)
						.message("Profil guncellendi.")
						.data(updated)
						.build()
		);
	}
}