package com.berkayb.soundconnect.modules.profile.StudioProfile.service;

import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;

import java.util.UUID;

public interface StudioProfileService {
	
	// kullanici icin manuel studioprofile olustur
	StudioProfileResponseDto createProfile(UUID userId, StudioProfileSaveRequestDto dto);
	
	// id ye gore studio profile getir.
	StudioProfileResponseDto getProfileByUserId(UUID userId);
	
	StudioProfileResponseDto updateProfile(UUID userId, StudioProfileSaveRequestDto dto);
	
	// TODO media ekleme, notification, comment vs eklencek.
}