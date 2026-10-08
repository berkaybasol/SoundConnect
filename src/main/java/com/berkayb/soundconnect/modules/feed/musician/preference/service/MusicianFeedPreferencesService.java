package com.berkayb.soundconnect.modules.feed.musician.preference.service;

import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesResponse;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesUpdate;

import java.util.UUID;

public interface MusicianFeedPreferencesService {
	MusicianFeedPreferencesResponse get(UUID userId);

	MusicianFeedPreferencesResponse update(UUID userId, MusicianFeedPreferencesUpdate update);
}
