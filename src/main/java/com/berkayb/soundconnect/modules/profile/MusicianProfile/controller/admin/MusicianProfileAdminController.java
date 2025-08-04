package com.berkayb.soundconnect.modules.profile.MusicianProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.MusicianProfile.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMIN / Musician Profile", description = "Admin tüm kullanıcıların müzisyen profillerini yönetir")
public class MusicianProfileAdminController {
	private final MusicianProfileService musicianProfileService;
	
	/**
	 * Admin istediği kullanıcının profilini görüntüler
	 */
	@GetMapping(BY_USER_ID)
	public ResponseEntity<BaseResponse<MusicianProfileResponseDto>> getMusicianProfileByUserId(
			@PathVariable UUID userId) {
		var dto = musicianProfileService.getProfileByUserId(userId);
		return ResponseEntity.ok(BaseResponse.<MusicianProfileResponseDto>builder()
		                                     .success(true).code(200).message("Profil getirildi").data(dto).build());
	}
	
	/**
	 * Admin istediği kullanıcının profilini günceller
	 */
	@PutMapping(ADMIN_UPDATE)
	public ResponseEntity<BaseResponse<MusicianProfileResponseDto>> updateMusicianProfileByUserId(
			@PathVariable UUID userId,
			@RequestBody MusicianProfileSaveRequestDto dto) {
		var updated = musicianProfileService.updateProfile(userId, dto);
		return ResponseEntity.ok(BaseResponse.<MusicianProfileResponseDto>builder()
		                                     .success(true).code(200).message("Profil güncellendi").data(updated).build());
	}
}