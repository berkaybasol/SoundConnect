package com.berkayb.soundconnect.tools.simulation.verify;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;

import java.util.List;
import java.util.Map;

public record SimulationFeedVerificationResult(Map<String, ObserverResult> observers) {
	public SimulationFeedVerificationResult {
		observers = observers == null ? Map.of() : Map.copyOf(observers);
	}

	public record ObserverResult(
		int pages,
		int items,
		long elapsedMillis,
		Map<MusicianFeedItemType, Long> composition,
		List<String> itemIds
	) {
		public ObserverResult {
			composition = composition == null ? Map.of() : Map.copyOf(composition);
			itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
		}
	}
}
