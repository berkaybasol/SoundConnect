package com.berkayb.soundconnect.modules.spotify.support;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpotifyPlaylistUrlParserTest {

	private static final String PLAYLIST_ID = "37i9dQZF1DXcBWIGoYBM5M";

	@Test
	void canonicalizesSharedAndLocalizedOpenSpotifyUrls() {
		var shared = SpotifyPlaylistUrlParser.parse(
				"  https://open.spotify.com/playlist/" + PLAYLIST_ID + "?si=abc#fragment  ");
		var localized = SpotifyPlaylistUrlParser.parse(
				"https://open.spotify.com/intl-tr/playlist/" + PLAYLIST_ID);

		assertThat(shared.playlistId()).isEqualTo(PLAYLIST_ID);
		assertThat(shared.canonicalUrl())
				.isEqualTo("https://open.spotify.com/playlist/" + PLAYLIST_ID);
		assertThat(localized).isEqualTo(shared);
	}

	@Test
	void rejectsNonPlaylistOrCallerControlledNetworkTargets() {
		assertInvalid("http://open.spotify.com/playlist/" + PLAYLIST_ID);
		assertInvalid("https://evil.test/playlist/" + PLAYLIST_ID);
		assertInvalid("https://open.spotify.com@evil.test/playlist/" + PLAYLIST_ID);
		assertInvalid("https://open.spotify.com/track/" + PLAYLIST_ID);
		assertInvalid("https://open.spotify.com/playlist/not-a-valid-id");
		assertInvalid("https://open.spotify.com/playlist/%2e%2e/track/" + PLAYLIST_ID);
		assertInvalid("https://spotify.link/example");
	}

	private void assertInvalid(String value) {
		assertThatThrownBy(() -> SpotifyPlaylistUrlParser.parse(value))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_PLAYLIST_URL_INVALID));
	}
}
