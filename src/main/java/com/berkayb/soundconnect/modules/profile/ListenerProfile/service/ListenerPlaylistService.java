package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerPlaylistsUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileOwnerResponseDto;

import java.util.UUID;

public interface ListenerPlaylistService {

	ListenerProfileOwnerResponseDto replacePlaylists(
			UUID userId,
			ListenerPlaylistsUpdateRequestDto request
	);
}
