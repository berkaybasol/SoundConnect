package com.berkayb.soundconnect.modules.search.service;

import com.berkayb.soundconnect.modules.search.dto.ProfileSearchItemDto;

import java.util.List;

public interface ProfileSearchService {
	List<ProfileSearchItemDto> searchProfiles(String query, int limit);
}
