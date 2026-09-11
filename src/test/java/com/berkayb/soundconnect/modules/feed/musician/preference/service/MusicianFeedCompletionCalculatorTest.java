package com.berkayb.soundconnect.modules.feed.musician.preference.service;

import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MusicianFeedCompletionCalculatorTest {

	@Test
	void blankProfileKeepsEveryActionableTaskInImpactOrderWithoutGating() {
		var completion = MusicianFeedCompletionCalculator.calculate(MusicianProfile.builder().build(), false, false);

		assertThat(completion.criteriaVersion()).isEqualTo(1);
		assertThat(completion.personalizationReadiness().complete()).isFalse();
		assertThat(completion.personalizationReadiness().completed()).isZero();
		assertThat(completion.personalizationReadiness().percentage()).isZero();
		assertThat(completion.publicProfileCompleteness().completed()).isZero();
		assertThat(completion.overall().total()).isEqualTo(5);
		assertThat(completion.incompleteTasks())
				.extracting(task -> task.code())
				.containsExactly(
						MusicianFeedCompletionTaskCode.OPPORTUNITY_CITY,
						MusicianFeedCompletionTaskCode.INSTRUMENTS,
						MusicianFeedCompletionTaskCode.STAGE_NAME_AND_BIO,
						MusicianFeedCompletionTaskCode.PORTFOLIO,
						MusicianFeedCompletionTaskCode.PROFILE_PHOTO_AND_SOCIAL_LINKS
				);
		assertThat(completion.incompleteTasks()).extracting(task -> task.order())
				.containsExactly(1, 2, 3, 4, 5);
	}

	@Test
	void instrumentsContributeToBothIndependentDimensions() {
		Instrument instrument = Instrument.builder().name("Gitar").build();
		instrument.setId(UUID.randomUUID());
		MusicianProfile profile = MusicianProfile.builder().instruments(Set.of(instrument)).build();

		var completion = MusicianFeedCompletionCalculator.calculate(profile, false, false);

		assertThat(completion.personalizationReadiness().completed()).isEqualTo(1);
		assertThat(completion.personalizationReadiness().percentage()).isEqualTo(50);
		assertThat(completion.publicProfileCompleteness().completed()).isEqualTo(1);
		assertThat(completion.publicProfileCompleteness().percentage()).isEqualTo(25);
		assertThat(completion.incompleteTasks()).noneMatch(task -> task.code() == MusicianFeedCompletionTaskCode.INSTRUMENTS);
	}

	@Test
	void everyRuleMustBeSatisfiedBeforeTheProfileIsDeclaredComplete() {
		Instrument instrument = Instrument.builder().name("Davul").build();
		instrument.setId(UUID.randomUUID());
		MusicianProfile profile = MusicianProfile.builder()
				.instruments(Set.of(instrument))
				.stageName("Stage")
				.description("Bio")
				.profilePictureMediaId(UUID.randomUUID())
				.instagramUrl("https://example.com/artist")
				.build();

		var completion = MusicianFeedCompletionCalculator.calculate(profile, true, true);

		assertThat(completion.personalizationReadiness().complete()).isTrue();
		assertThat(completion.publicProfileCompleteness().complete()).isTrue();
		assertThat(completion.overall().complete()).isTrue();
		assertThat(completion.overall().percentage()).isEqualTo(100);
		assertThat(completion.incompleteTasks()).isEmpty();
	}

	@Test
	void whitespaceAndOnlyHalfOfCombinedTasksDoNotInflateCompleteness() {
		MusicianProfile profile = MusicianProfile.builder()
				.stageName("  ")
				.description("bio")
				.profilePictureMediaId(UUID.randomUUID())
				.instagramUrl(" ")
				.build();

		var completion = MusicianFeedCompletionCalculator.calculate(profile, true, false);

		assertThat(completion.incompleteTasks()).extracting(task -> task.code())
				.contains(MusicianFeedCompletionTaskCode.STAGE_NAME_AND_BIO,
						MusicianFeedCompletionTaskCode.PROFILE_PHOTO_AND_SOCIAL_LINKS);
		assertThat(completion.overall().completed()).isEqualTo(1);
	}
}
