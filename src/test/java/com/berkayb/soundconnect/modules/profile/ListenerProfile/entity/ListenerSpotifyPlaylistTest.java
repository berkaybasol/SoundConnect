package com.berkayb.soundconnect.modules.profile.ListenerProfile.entity;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListenerSpotifyPlaylistTest {

	private static final String ID = "37i9dQZF1DXcBWIGoYBM5M";

	@Test
	void aggregateFactoryNormalizesAndStoresOnlyCanonicalMetadata() {
		ListenerProfile profile = ListenerProfile.builder().id(UUID.randomUUID()).build();
		var playlist = ListenerSpotifyPlaylist.create(
				profile,
				new SpotifyPlaylistMetadataDto(
						ID,
						"  Playlist  ",
						"https://i.scdn.co/image/cover",
						"https://open.spotify.com/playlist/" + ID),
				0
		);

		assertThat(playlist.getListenerProfile()).isSameAs(profile);
		assertThat(playlist.getTitle()).isEqualTo("Playlist");
		assertThat(playlist.getSpotifyPlaylistId()).isEqualTo(ID);
		assertThat(playlist.getPosition()).isZero();
	}

	@Test
	void aggregateFactoryRejectsUntrustedArtworkEvenForAnInternalCaller() {
		ListenerProfile profile = ListenerProfile.builder().id(UUID.randomUUID()).build();

		assertThatThrownBy(() -> ListenerSpotifyPlaylist.create(
				profile,
				new SpotifyPlaylistMetadataDto(
						ID,
						"Playlist",
						"https://i.scdn.co.evil.test/image/cover",
						"https://open.spotify.com/playlist/" + ID),
				0
		)).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Invalid listener Spotify playlist snapshot");
	}
}
