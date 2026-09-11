package com.berkayb.soundconnect.modules.feed.musician.preference.service;

import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionResponse;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;

import java.util.ArrayList;
import java.util.List;

import static com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode.INSTRUMENTS;
import static com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode.OPPORTUNITY_CITY;
import static com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode.PORTFOLIO;
import static com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode.PROFILE_PHOTO_AND_SOCIAL_LINKS;
import static com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode.STAGE_NAME_AND_BIO;

/** Pure, versioned completion policy; clients never recreate these rules. */
final class MusicianFeedCompletionCalculator {
	static final int CRITERIA_VERSION = 1;
	private static final int TOTAL_PERSONALIZATION_TASKS = 2;
	private static final int TOTAL_PUBLIC_PROFILE_TASKS = 4;
	private static final int TOTAL_CAROUSEL_TASKS = 5;

	private MusicianFeedCompletionCalculator() {}

	static MusicianFeedCompletionResponse calculate(
			MusicianProfile profile,
			boolean hasOpportunityCity,
			boolean hasPublicPortfolio
	) {
		boolean instruments = profile.getInstruments() != null && !profile.getInstruments().isEmpty();
		boolean stageNameAndBio = hasText(profile.getStageName()) && hasText(profile.getDescription());
		boolean photoAndSocialLinks = profile.getProfilePictureMediaId() != null && hasSocialLink(profile);

		List<MusicianFeedCompletionResponse.IncompleteTask> incomplete = new ArrayList<>();
		addIfIncomplete(incomplete, OPPORTUNITY_CITY, 1, hasOpportunityCity);
		addIfIncomplete(incomplete, INSTRUMENTS, 2, instruments);
		addIfIncomplete(incomplete, STAGE_NAME_AND_BIO, 3, stageNameAndBio);
		addIfIncomplete(incomplete, PORTFOLIO, 4, hasPublicPortfolio);
		addIfIncomplete(incomplete, PROFILE_PHOTO_AND_SOCIAL_LINKS, 5, photoAndSocialLinks);

		int personalizationCompleted = count(hasOpportunityCity, instruments);
		// Instruments improve both matching and the public professional profile.
		int publicProfileCompleted = count(instruments, stageNameAndBio, hasPublicPortfolio, photoAndSocialLinks);
		int overallCompleted = TOTAL_CAROUSEL_TASKS - incomplete.size();

		return new MusicianFeedCompletionResponse(
				CRITERIA_VERSION,
				progress(personalizationCompleted, TOTAL_PERSONALIZATION_TASKS),
				progress(publicProfileCompleted, TOTAL_PUBLIC_PROFILE_TASKS),
				progress(overallCompleted, TOTAL_CAROUSEL_TASKS),
				List.copyOf(incomplete)
		);
	}

	private static void addIfIncomplete(
			List<MusicianFeedCompletionResponse.IncompleteTask> incomplete,
			MusicianFeedCompletionTaskCode code,
			int order,
			boolean completed
	) {
		if (!completed) incomplete.add(new MusicianFeedCompletionResponse.IncompleteTask(code, order));
	}

	private static MusicianFeedCompletionResponse.Progress progress(int completed, int total) {
		return new MusicianFeedCompletionResponse.Progress(
				completed == total,
				completed,
				total,
				completed * 100 / total
		);
	}

	private static int count(boolean... values) {
		int count = 0;
		for (boolean value : values) if (value) count++;
		return count;
	}

	private static boolean hasSocialLink(MusicianProfile profile) {
		return hasText(profile.getInstagramUrl())
				|| hasText(profile.getYoutubeUrl())
				|| hasText(profile.getSoundcloudUrl());
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
