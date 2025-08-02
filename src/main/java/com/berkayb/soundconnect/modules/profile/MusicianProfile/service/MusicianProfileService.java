package com.berkayb.soundconnect.modules.profile.MusicianProfile.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;

import java.util.UUID;

public interface MusicianProfileService {
	MusicianProfileResponseDto createProfile(UUID userId, MusicianProfileSaveRequestDto dto);
	
	MusicianProfileResponseDto getProfileByUserId(UUID userId);
	
	MusicianProfileResponseDto updateProfile(UUID userId, MusicianProfileSaveRequestDto dto);
	
	
}