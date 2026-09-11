package com.berkayb.soundconnect.modules.feed.musician.preference.dto;

import java.util.List;

public record MusicianFeedPreferencesResponse(
		int contractVersion,
		long version,
		OpportunityCitySummary opportunityCity,
		List<MusicianInstrumentSummary> instruments,
		MusicianFeedCompletionResponse completion
) {}
