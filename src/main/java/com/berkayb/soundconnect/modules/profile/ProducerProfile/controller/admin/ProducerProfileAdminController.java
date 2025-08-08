package com.berkayb.soundconnect.modules.profile.ProducerProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.request.ProducerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.response.ProducerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.service.ProducerProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.ProducerProfile.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMIN / Producer Profile", description = "Admin tüm kullanıcıların producer profillerini yönetir")
public class ProducerProfileAdminController {
	private final ProducerProfileService producerProfileService;
	
	
	@GetMapping(BY_USER_ID)
	public ResponseEntity<BaseResponse<ProducerProfileResponseDto>> getProducerProfileByUserId(
			@PathVariable UUID userId) {
		var dto = producerProfileService.getProfileByUserId(userId);
		return ResponseEntity.ok(BaseResponse.<ProducerProfileResponseDto>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Profil getirildi.")
				                         .data(dto)
				                         .build());
	}
	
	@PutMapping(ADMIN_UPDATE)
	public ResponseEntity<BaseResponse<ProducerProfileResponseDto>> updateProducerProfile(
			@PathVariable UUID userId,
			@RequestBody ProducerProfileSaveRequestDto dto) {
				var uptated = producerProfileService.updateProfile(userId, dto);
				return ResponseEntity.ok(BaseResponse.<ProducerProfileResponseDto>builder()
						                         .success(true)
						                         .code(200)
						                         .message("Profil guncellendi")
						                         .data(uptated)
						                         .build());
	}
}