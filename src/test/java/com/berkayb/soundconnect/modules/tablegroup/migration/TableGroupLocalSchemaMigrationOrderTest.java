package com.berkayb.soundconnect.modules.tablegroup.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TableGroupLocalSchemaMigrationOrderTest {

	@Test
	void localBootstrapAppliesTableGroupMigrationsInReleaseOrder() throws Exception {
		String devScript = Files.readString(Path.of(
				System.getProperty("user.dir"), "scripts", "dev.ps1"));
		String hardening = "2026-08-17-tablegroup-hardening.sql";
		String game = "2026-08-30-tablegroup-who-pays-game.sql";
		String globalFeed = "2026-08-31-tablegroup-global-feed.sql";
		String description = "2026-09-01-tablegroup-description.sql";
		String optionalVenue = "2026-09-01-tablegroup-optional-venue.sql";
		String meetingTime = "2026-09-01-tablegroup-meeting-time.sql";
		String chatIdempotency = "2026-09-02-tablegroup-chat-idempotency.sql";
		String notificationTypeSpelling =
				"2026-09-02-tablegroup-notification-type-spelling.sql";
		String strictCreateContract =
				"2026-09-02-tablegroup-create-contract-strict.sql";
		String listenerGhostProfile =
				"2026-09-03-listener-ghost-profile.sql";
		String listenerSpotifyPlaylists =
				"2026-09-04-listener-spotify-playlists.sql";

		assertThat(devScript).contains(
				hardening,
				game,
				globalFeed,
				description,
				optionalVenue,
				meetingTime,
				chatIdempotency,
				notificationTypeSpelling,
				strictCreateContract,
				listenerGhostProfile,
				listenerSpotifyPlaylists
		);
		assertThat(devScript.indexOf(hardening)).isLessThan(devScript.indexOf(game));
		assertThat(devScript.indexOf(game)).isLessThan(devScript.indexOf(globalFeed));
		assertThat(devScript.indexOf(globalFeed)).isLessThan(devScript.indexOf(description));
		assertThat(devScript.indexOf(description)).isLessThan(devScript.indexOf(optionalVenue));
		assertThat(devScript.indexOf(optionalVenue)).isLessThan(devScript.indexOf(meetingTime));
		assertThat(devScript.indexOf(meetingTime)).isLessThan(devScript.indexOf(chatIdempotency));
		assertThat(devScript.indexOf(chatIdempotency))
				.isLessThan(devScript.indexOf(notificationTypeSpelling));
		assertThat(devScript.indexOf(notificationTypeSpelling))
				.isLessThan(devScript.indexOf(strictCreateContract));
		assertThat(devScript.indexOf(strictCreateContract))
				.isLessThan(devScript.indexOf(listenerGhostProfile));
		assertThat(devScript.indexOf(listenerGhostProfile))
				.isLessThan(devScript.indexOf(listenerSpotifyPlaylists));
		assertThat(devScript).contains(
				"Local Studio, Collab, TableGroup, and listener-profile schemas are ready.");
	}
}
