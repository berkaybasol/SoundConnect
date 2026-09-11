package com.berkayb.soundconnect.modules.feed.musician.preference.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MusicianFeedPreferencesMigrationOrderTest {
	@Test
	void localBootstrapRegistersTheAdditiveMigrationExactlyOnce() throws Exception {
		String script = Files.readString(Path.of("scripts/dev.ps1"));
		String migration = "2026-09-11-musician-feed-preferences.sql";
		assertThat(occurrences(script, migration)).isEqualTo(1);
		assertThat(script.indexOf(migration))
				.isGreaterThan(script.indexOf("2026-09-10-tablegroup-profile-share-history.sql"));
	}

	private static int occurrences(String text, String value) {
		int count = 0;
		for (int index = 0; (index = text.indexOf(value, index)) >= 0; index += value.length()) count++;
		return count;
	}
}
