package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.ListenerProfileController;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.ProfileListener.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "ListenerProfile", description = "Listener profile management endpoints")
public class ListenerProfileControllerImpl implements ListenerProfileController {
	private final ListenerProfileService listenerProfileService;
	
	// Kendi profilini getir
	@GetMapping(GET_MY_PROFILE)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> getMyProfile() {
		UUID userId = ((UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal())
				.getUser().getId();
		ListenerProfileResponseDto response = listenerProfileService.getProfileByUserId(userId);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileResponseDto>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Listener profil getirildi.")
		                                     .data(response)
		                                     .build());
	}
	
	// Başkasının profilini getir
	@GetMapping(GET_PROFILE_BY_ID)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> getProfileById(@PathVariable("id") UUID id) {
		ListenerProfileResponseDto response = listenerProfileService.getProfileByUserId(id);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileResponseDto>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Listener profil getirildi.")
		                                     .data(response)
		                                     .build());
	}
	
	@PostMapping(CREATE)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> createProfile(
			@RequestParam UUID userId,
			@RequestBody ListenerSaveRequestDto dto) {
		ListenerProfileResponseDto response = listenerProfileService.createProfile(userId, dto);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileResponseDto>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Listener profile created")
		                                     .data(response)
		                                     .build());
	}
	
	// Kendi profilini güncelle (opsiyonel)
	@PutMapping(UPDATE_MY_PROFILE)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> updateMyProfile(
			@RequestBody ListenerSaveRequestDto dto) {
		UUID userId = ((UserDetailsImpl) SecurityContextHolder.getContext().getAuthentication().getPrincipal())
				.getUser().getId();
		ListenerProfileResponseDto response = listenerProfileService.updateProfile(userId, dto);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileResponseDto>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Listener profile updated")
		                                     .data(response)
		                                     .build());
	}
	
	// Herhangi bir profile güncelleme (admin/owner future)
	@PutMapping(UPDATE)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> updateProfile(
			@PathVariable("id") UUID id,
			@RequestBody ListenerSaveRequestDto dto) {
		ListenerProfileResponseDto response = listenerProfileService.updateProfile(id, dto);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileResponseDto>builder()
		                                     .success(true)
		                                     .code(200)
		                                     .message("Listener profile updated")
		                                     .data(response)
		                                     .build());
	}
}