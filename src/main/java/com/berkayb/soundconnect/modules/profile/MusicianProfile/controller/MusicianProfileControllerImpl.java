package com.berkayb.soundconnect.modules.profile.MusicianProfile.controller;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.util.JwtUtil;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.ProfileMusician.*;



@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Musician Profile", description = "Musician Profile management endpoints")
public class MusicianProfileControllerImpl implements MusicianProfileController {
	private final MusicianProfileService musicianProfileService;
	private final JwtUtil jwtUtil;
	
	@PostMapping(CREATE)
	@Override
	public ResponseEntity<BaseResponse<MusicianProfileResponseDto>> createProfile(@RequestBody MusicianProfileSaveRequestDto dto, HttpServletRequest request) {
		UUID userId = jwtUtil.extractUserIdFromRequest(request);
		MusicianProfileResponseDto profile = musicianProfileService.createProfile(userId, dto);
		return ResponseEntity.ok(BaseResponse.<MusicianProfileResponseDto>builder()
		                                     .success(true)
		                                     .message("Profil başarıyla oluşturuldu.")
		                                     .data(profile)
		                                     .build());
	}
	
	@GetMapping(GET_MY_PROFILE)
	@Override
	public ResponseEntity<BaseResponse<MusicianProfileResponseDto>> getProfile(HttpServletRequest request) {
		UUID userId = jwtUtil.extractUserIdFromRequest(request);
		MusicianProfileResponseDto profile = musicianProfileService.getProfileByUserId(userId);
		return ResponseEntity.ok(BaseResponse.<MusicianProfileResponseDto>builder()
		                                     .success(true)
		                                     .message("Profil getirildi.")
		                                     .data(profile)
		                                     .build());
	}
	
	@PutMapping(UPDATE)
	@Override
	public ResponseEntity<BaseResponse<MusicianProfileResponseDto>> updateProfile(@RequestBody MusicianProfileSaveRequestDto dto,HttpServletRequest request) {
		UUID userId = jwtUtil.extractUserIdFromRequest(request);
		MusicianProfileResponseDto profile = musicianProfileService.updateProfile(userId, dto);
		return ResponseEntity.ok(BaseResponse.<MusicianProfileResponseDto>builder()
		                                     .success(true)
		                                     .message("Profil başarıyla güncellendi.")
		                                     .data(profile)
		                                     .build());
	}
}