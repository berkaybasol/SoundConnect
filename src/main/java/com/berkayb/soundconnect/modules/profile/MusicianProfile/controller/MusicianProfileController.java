package com.berkayb.soundconnect.modules.profile.MusicianProfile.controller;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;

public interface MusicianProfileController {
	ResponseEntity<BaseResponse<MusicianProfileResponseDto>> createProfile(MusicianProfileSaveRequestDto dto, HttpServletRequest request);
	
	ResponseEntity<BaseResponse<MusicianProfileResponseDto>> getProfile(HttpServletRequest request);
	
	ResponseEntity<BaseResponse<MusicianProfileResponseDto>> updateProfile(MusicianProfileSaveRequestDto dto, HttpServletRequest request);
	
}