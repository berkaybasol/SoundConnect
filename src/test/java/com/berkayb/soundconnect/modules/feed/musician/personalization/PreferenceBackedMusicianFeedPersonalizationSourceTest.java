package com.berkayb.soundconnect.modules.feed.musician.personalization;

import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionResponse;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesResponse;
import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreferenceBackedMusicianFeedPersonalizationSourceTest {
	@Test
	void emitsBioOnlyCompletionCopyAndKeepsTheLegacyCodeReadable() {
		MusicianFeedPreferencesService preferences = mock(MusicianFeedPreferencesService.class);
		UUID userId = UUID.randomUUID();
		var source = new PreferenceBackedMusicianFeedPersonalizationSource(preferences);

		when(preferences.get(userId)).thenReturn(response(MusicianFeedCompletionTaskCode.BIO));
		var bioTask = source.load(userId, UUID.randomUUID()).completion().tasks().getFirst();
		assertThat(bioTask.code()).isEqualTo("BIO");
		assertThat(bioTask.title()).isEqualTo("Biyografini tamamla");
		assertThat(bioTask.title()).doesNotContainIgnoringCase("sahne");

		when(preferences.get(userId)).thenReturn(response(
				MusicianFeedCompletionTaskCode.STAGE_NAME_AND_BIO));
		var legacyTask = source.load(userId, UUID.randomUUID()).completion().tasks().getFirst();
		assertThat(legacyTask.code()).isEqualTo("STAGE_NAME_AND_BIO");
		assertThat(legacyTask.title()).isEqualTo("Biyografini tamamla");
	}

	private static MusicianFeedPreferencesResponse response(MusicianFeedCompletionTaskCode code) {
		var progress = new MusicianFeedCompletionResponse.Progress(false, 0, 1, 0);
		var completion = new MusicianFeedCompletionResponse(
				2, progress, progress, progress,
				List.of(new MusicianFeedCompletionResponse.IncompleteTask(code, 3)));
		return new MusicianFeedPreferencesResponse(1, 0, null, List.of(), completion);
	}
}
