package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerAvatarUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerVisibilityUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileOwnerResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfilePublicResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileSearchItemDto;

import java.util.List;
import java.util.UUID;

public interface ListenerProfileService {
	
	// profil olusturur
	ListenerProfileOwnerResponseDto createProfile(UUID userId, ListenerSaveRequestDto dto);

	ListenerProfileOwnerResponseDto getMyProfile(UUID userId);

	ListenerProfileOwnerResponseDto updateMyProfile(UUID userId, ListenerSaveRequestDto dto);

	ListenerProfileOwnerResponseDto updateAvatar(UUID userId, ListenerAvatarUpdateRequestDto dto);

	ListenerProfileOwnerResponseDto updateVisibility(UUID userId, ListenerVisibilityUpdateRequestDto dto);
	
	// profili userId'ye gore getirir
	ListenerProfileResponseDto getProfileByUserId (UUID userId);
	
	// guncelle
	public ListenerProfileResponseDto updateProfile(UUID userId, ListenerSaveRequestDto dto);
	
	ListenerProfilePublicResponseDto getProfileByProfileId(UUID profileId);
	
	List<ListenerProfileSearchItemDto> searchProfiles(String query);
	
	
}
