package com.berkayb.soundconnect.modules.spotify.service;

import com.berkayb.soundconnect.modules.spotify.client.SpotifyApiClient;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpotifyServiceImplTest {

	@Test
	void playlistLookupCanonicalizesTheUrlAndDelegatesOnlyTheValidatedId() {
		SpotifyApiClient apiClient = mock(SpotifyApiClient.class);
		SpotifyServiceImpl service = new SpotifyServiceImpl(apiClient);
		String id = "37i9dQZF1DXcBWIGoYBM5M";
		var expected = new SpotifyPlaylistMetadataDto(
				id, "Playlist", "https://i.scdn.co/image/cover",
				"https://open.spotify.com/playlist/" + id);
		when(apiClient.getPlaylistMetadata(id)).thenReturn(expected);

		assertThat(service.getPlaylistMetadata(
				"https://open.spotify.com/intl-tr/playlist/" + id + "?si=share"))
				.isSameAs(expected);
		verify(apiClient).getPlaylistMetadata(id);
	}

	@Test
	void invalidPlaylistUrlNeverReachesTheNetworkClient() {
		SpotifyApiClient apiClient = mock(SpotifyApiClient.class);
		SpotifyServiceImpl service = new SpotifyServiceImpl(apiClient);

		assertThatThrownBy(() -> service.getPlaylistMetadata(
				"https://evil.test/playlist/37i9dQZF1DXcBWIGoYBM5M"))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_PLAYLIST_URL_INVALID));
		verifyNoInteractions(apiClient);
	}

	@Test
	void playlistBatchCanonicalizesEveryUrlAndDelegatesInOrder() {
		SpotifyApiClient apiClient = mock(SpotifyApiClient.class);
		SpotifyServiceImpl service = new SpotifyServiceImpl(apiClient);
		String firstId = "37i9dQZF1DXcBWIGoYBM5M";
		String secondId = "0vvXsWCC9xrXsKd4FyS8kM";
		var expected = List.of(
				metadata(firstId, "First"),
				metadata(secondId, "Second")
		);
		when(apiClient.getPlaylistMetadataBatch(List.of(firstId, secondId)))
				.thenReturn(expected);

		assertThat(service.getPlaylistMetadataBatch(List.of(
				"https://open.spotify.com/playlist/" + firstId + "?si=share",
				"https://open.spotify.com/intl-tr/playlist/" + secondId)))
				.isSameAs(expected);
		verify(apiClient).getPlaylistMetadataBatch(List.of(firstId, secondId));
	}

	private SpotifyPlaylistMetadataDto metadata(String id, String title) {
		return new SpotifyPlaylistMetadataDto(
				id,
				title,
				"https://i.scdn.co/image/" + id,
				"https://open.spotify.com/playlist/" + id
		);
	}
}
