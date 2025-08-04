package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.admin;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.ListenerProfile.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMIN / Listener Profile", description = "Endpoints for Admin users to manage Listener profiles")
public class ListenerProfileAdminController {
private final ListenerProfileService listenerProfileService;
	
	// İstediği kullanıcının profilini görüntüle
	@GetMapping(BY_USER_ID)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> getListenerProfileByUserId(
			@PathVariable UUID userId) {
		ListenerProfileResponseDto response = listenerProfileService.getProfileByUserId(userId);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileResponseDto>builder()
		                                     .success(true).code(200).message("Profil getirildi").data(response).build());
	}
	
	// İstediği kullanıcının profilini güncelle
	@PutMapping(ADMIN_UPDATE)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> updateListenerProfileByUserId(
			@PathVariable UUID userId,
			@RequestBody ListenerSaveRequestDto dto) {
		ListenerProfileResponseDto response = listenerProfileService.updateProfile(userId, dto);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileResponseDto>builder()
		                                     .success(true).code(200).message("Profil güncellendi").data(response).build());
	}
}