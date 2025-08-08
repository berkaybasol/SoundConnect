package com.berkayb.soundconnect.modules.profile.OrganizerProfile.service;


import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.request.OrganizerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.dto.response.OrganizerProfileResponseDto;

import java.util.UUID;

public interface OrganizerProfileService {
	// factory de kullaniyoz
	OrganizerProfileResponseDto createProfile(UUID userId, OrganizerProfileSaveRequestDto dto);
	
	OrganizerProfileResponseDto getProfileByUserId(UUID userId);
	
	OrganizerProfileResponseDto updateProfile(UUID userId, OrganizerProfileSaveRequestDto dto);
	
	//TODO media ekleme, notification, comment vs.
	
}