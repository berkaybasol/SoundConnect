package com.berkayb.soundconnect.modules.profile.MusicianProfile.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.berkayb.soundconnect.shared.constant.EndPoints.MusicianProfile.*;

@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR USERS / Musician Profile", description = "User kendi müzisyen profilini yönetir")
public class MusicianProfileUserController {
	private final MusicianProfileService musicianProfileService;
	
	/**
	 * Kullanıcı kendi profilini getirir
	 */
	@PreAuthorize("hasRole('MUSICIAN')")
	@GetMapping(ME)
	public ResponseEntity<BaseResponse<MusicianProfileResponseDto>> getMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails) {
		var dto = musicianProfileService.getProfileByUserId(userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<MusicianProfileResponseDto>builder()
		                                     .success(true).code(200).message("Profil getirildi").data(dto).build());
	}
	
	/**
	 * Kullanıcı kendi profili oluşturur
	 */
	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping(CREATE)
	public ResponseEntity<BaseResponse<MusicianProfileResponseDto>> createMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@Valid @RequestBody MusicianProfileSaveRequestDto dto) {
		var created = musicianProfileService.createProfile(userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<MusicianProfileResponseDto>builder()
		                                     .success(true).code(201).message("Profil oluşturuldu").data(created).build());
	}
	
	/**
	 * Kullanıcı kendi profilini günceller
	 */
	@PreAuthorize("hasRole('MUSICIAN')")
	@PutMapping(UPDATE)
	public ResponseEntity<BaseResponse<MusicianProfileResponseDto>> updateMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@Valid @RequestBody MusicianProfileSaveRequestDto dto) {
		var updated = musicianProfileService.updateProfile(userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<MusicianProfileResponseDto>builder()
		                                     .success(true).code(200).message("Profil güncellendi").data(updated).build());
	}
}
