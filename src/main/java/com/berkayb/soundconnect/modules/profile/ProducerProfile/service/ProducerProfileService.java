package com.berkayb.soundconnect.modules.profile.ProducerProfile.service;

import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.request.ProducerProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.dto.response.ProducerProfileResponseDto;

import java.util.UUID;

public interface ProducerProfileService {
	// factary icin
	ProducerProfileResponseDto createProfile(UUID userId, ProducerProfileSaveRequestDto dto);
	
	ProducerProfileResponseDto getProfileByUserId(UUID userId);
	
	ProducerProfileResponseDto updateProfile(UUID userId, ProducerProfileSaveRequestDto dto);
	
	//TODO media, notofication, comment..
}