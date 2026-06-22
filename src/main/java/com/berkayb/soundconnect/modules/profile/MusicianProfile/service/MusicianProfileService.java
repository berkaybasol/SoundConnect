package com.berkayb.soundconnect.modules.profile.MusicianProfile.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileSearchItemDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;

import java.util.List;
import java.util.UUID;

public interface MusicianProfileService {
	MusicianProfileResponseDto createProfile(UUID userId, MusicianProfileSaveRequestDto dto);
	
	MusicianProfileResponseDto getProfileByUserId(UUID userId);
	
	MusicianProfileResponseDto updateProfile(UUID userId, MusicianProfileSaveRequestDto dto);
	
	MusicianProfile getProfileEntity(UUID profileId);
	
	// id ye gore getir
	MusicianProfileResponseDto getProfileByProfileId(UUID profileId);
	
	List<MusicianProfileSearchItemDto> searchProfiles(String query);
}