package com.berkayb.soundconnect.modules.search.service;

import com.berkayb.soundconnect.modules.search.dto.ProfileSearchItemDto;
import com.berkayb.soundconnect.modules.search.enums.ProfileSearchType;

import java.util.List;
import java.util.Set;

public interface ProfileSearchService {
	List<ProfileSearchItemDto> searchProfiles(String query, int limit);

	List<ProfileSearchItemDto> searchProfiles(
			String query,
			int limit,
			Set<ProfileSearchType> types
	);
}
