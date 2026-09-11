package com.berkayb.soundconnect.modules.feed.musician.preference.dto;

import java.util.List;

public record MusicianFeedCompletionResponse(
		int criteriaVersion,
		Progress personalizationReadiness,
		Progress publicProfileCompleteness,
		Progress overall,
		List<IncompleteTask> incompleteTasks
) {
	public record Progress(boolean complete, int completed, int total, int percentage) {}

	public record IncompleteTask(MusicianFeedCompletionTaskCode code, int order) {}
}
