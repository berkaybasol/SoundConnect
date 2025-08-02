package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;

import java.util.UUID;

public interface ListenerProfileService {
	
	// profil olusturur
	ListenerProfileResponseDto createProfile (UUID userId, ListenerSaveRequestDto dto);
	
	// profili userId'ye gore getirir
	ListenerProfileResponseDto getProfileByUserId (UUID userId);
	
	// guncelle
	public ListenerProfileResponseDto updateProfile(UUID userId, ListenerSaveRequestDto dto);
	
}