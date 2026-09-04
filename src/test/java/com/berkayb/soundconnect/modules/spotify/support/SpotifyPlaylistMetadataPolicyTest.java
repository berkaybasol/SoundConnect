package com.berkayb.soundconnect.modules.spotify.support;

import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpotifyPlaylistMetadataPolicyTest {

	private static final String ID = "37i9dQZF1DXcBWIGoYBM5M";
	private static final String CANONICAL = "https://open.spotify.com/playlist/" + ID;

	@Test
	void normalizesACompleteServerOwnedSnapshot() {
		var result = SpotifyPlaylistMetadataPolicy.validateAndNormalize(
				new SpotifyPlaylistMetadataDto(
						ID,
						"  Playlist  ",
						"https://image-cdn-ak.spotifycdn.com/image/cover?size=300",
						CANONICAL
				));

		assertThat(result.title()).isEqualTo("Playlist");
		assertThat(result.coverImageUrl())
				.isEqualTo("https://image-cdn-ak.spotifycdn.com/image/cover?size=300");
		assertThat(result.spotifyUrl()).isEqualTo(CANONICAL);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"http://i.scdn.co/image/cover",
			"https://i.scdn.co.evil.test/image/cover",
			"https://spotifycdn.com@evil.test/image/cover",
			"https://i.scdn.co:444/image/cover",
			"https://i.scdn.co/image/cover#fragment"
	})
	void rejectsArtworkOutsideTheStrictSpotifyCdnBoundary(String coverImageUrl) {
		assertInvalid(new SpotifyPlaylistMetadataDto(
				ID, "Playlist", coverImageUrl, CANONICAL));
	}

	@Test
	void rejectsMismatchedCanonicalUrlAndInvalidPlaylistId() {
		assertInvalid(new SpotifyPlaylistMetadataDto(
				ID,
				"Playlist",
				"https://i.scdn.co/image/cover",
				CANONICAL + "?si=client"));
		assertInvalid(new SpotifyPlaylistMetadataDto(
				"short",
				"Playlist",
				"https://i.scdn.co/image/cover",
				"https://open.spotify.com/playlist/short"));
	}

	private void assertInvalid(SpotifyPlaylistMetadataDto metadata) {
		assertThatThrownBy(() -> SpotifyPlaylistMetadataPolicy.validateAndNormalize(metadata))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID));
	}
}
