package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

public interface ListenerProfileController {
	// kendi profilini goruntuleme
	ResponseEntity<BaseResponse<ListenerProfileResponseDto>> getMyProfile();
	
	// baskasinin profilini goruntuleme
	ResponseEntity<BaseResponse<ListenerProfileResponseDto>> getProfileById(UUID userId);
	
	ResponseEntity<BaseResponse<ListenerProfileResponseDto>> createProfile(UUID userId, ListenerSaveRequestDto dto);
	
	ResponseEntity<BaseResponse<ListenerProfileResponseDto>> updateProfile(UUID userId, ListenerSaveRequestDto dto);
}