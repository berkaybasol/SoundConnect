package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerPlaylistsUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileOwnerResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListenerPlaylistServiceImplTest {

	private static final String FIRST_ID = "37i9dQZF1DXcBWIGoYBM5M";
	private static final String SECOND_ID = "0vvXsWCC9xrXsKd4FyS8kM";

	@Mock SpotifyService spotifyService;
	@Mock ListenerPlaylistMutationService mutationService;
	@Mock ListenerProfileService listenerProfileService;
	@InjectMocks ListenerPlaylistServiceImpl service;

	@Test
	void replacesInRequestedOrderUsingOnlyServerHydratedMetadata() {
		UUID userId = UUID.randomUUID();
		String firstUrl = canonical(FIRST_ID);
		String secondUrl = canonical(SECOND_ID);
		var request = new ListenerPlaylistsUpdateRequestDto(
				List.of(firstUrl + "?si=shared", secondUrl),
				4L
		);
		var first = metadata(FIRST_ID, "First");
		var second = metadata(SECOND_ID, "Second");
		var owner = ownerResponse(userId, 5L);
		when(mutationService.prepareReplacement(userId, List.of(FIRST_ID, SECOND_ID), 4L))
				.thenReturn(new ListenerPlaylistMutationService.ReplacementPlan(true, List.of()));
		when(spotifyService.getPlaylistMetadataBatch(List.of(firstUrl, secondUrl)))
				.thenReturn(List.of(first, second));
		when(listenerProfileService.getMyProfile(userId)).thenReturn(owner);

		var result = service.replacePlaylists(userId, request);

		assertThat(result).isSameAs(owner);
		verify(mutationService).replace(userId, List.of(first, second), 4L);
	}

	@Test
	void exactRetrySkipsSpotifyAndReturnsCurrentOwnerProjection() {
		UUID userId = UUID.randomUUID();
		var request = new ListenerPlaylistsUpdateRequestDto(List.of(canonical(FIRST_ID)), 2L);
		var owner = ownerResponse(userId, 3L);
		when(mutationService.prepareReplacement(userId, List.of(FIRST_ID), 2L))
				.thenReturn(new ListenerPlaylistMutationService.ReplacementPlan(false, List.of()));
		when(listenerProfileService.getMyProfile(userId)).thenReturn(owner);

		assertThat(service.replacePlaylists(userId, request)).isSameAs(owner);

		verifyNoInteractions(spotifyService);
	}

	@Test
	void duplicateCanonicalIdsAreRejectedBeforeDatabaseOrSpotifyAccess() {
		UUID userId = UUID.randomUUID();
		var request = new ListenerPlaylistsUpdateRequestDto(
				List.of(canonical(FIRST_ID), canonical(FIRST_ID) + "?si=other"),
				0L
		);

		assertThatThrownBy(() -> service.replacePlaylists(userId, request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.LISTENER_PLAYLIST_DUPLICATE));

		verifyNoInteractions(spotifyService, mutationService, listenerProfileService);
	}

	@Test
	void rejectsAnInconsistentUpstreamSnapshotBeforeMutation() {
		UUID userId = UUID.randomUUID();
		String url = canonical(FIRST_ID);
		var request = new ListenerPlaylistsUpdateRequestDto(List.of(url), 1L);
		when(mutationService.prepareReplacement(userId, List.of(FIRST_ID), 1L))
				.thenReturn(new ListenerPlaylistMutationService.ReplacementPlan(true, List.of()));
		when(spotifyService.getPlaylistMetadataBatch(List.of(url)))
				.thenReturn(List.of(metadata(SECOND_ID, "Wrong")));

		assertThatThrownBy(() -> service.replacePlaylists(userId, request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_PLAYLIST_METADATA_INVALID));
	}

	@Test
	void reorderReusesPersistedAuthoritativeSnapshotsWithoutCallingSpotify() {
		UUID userId = UUID.randomUUID();
		var first = metadata(FIRST_ID, "First");
		var second = metadata(SECOND_ID, "Second");
		var request = new ListenerPlaylistsUpdateRequestDto(
				List.of(canonical(SECOND_ID), canonical(FIRST_ID)), 4L);
		var owner = ownerResponse(userId, 5L);
		when(mutationService.prepareReplacement(
				userId, List.of(SECOND_ID, FIRST_ID), 4L))
				.thenReturn(new ListenerPlaylistMutationService.ReplacementPlan(
						true, List.of(first, second)));
		when(listenerProfileService.getMyProfile(userId)).thenReturn(owner);

		assertThat(service.replacePlaylists(userId, request)).isSameAs(owner);

		verifyNoInteractions(spotifyService);
		verify(mutationService).replace(userId, List.of(second, first), 4L);
	}

	@Test
	void addingOnePlaylistHydratesOnlyTheNewId() {
		UUID userId = UUID.randomUUID();
		var first = metadata(FIRST_ID, "First");
		var second = metadata(SECOND_ID, "Second");
		var request = new ListenerPlaylistsUpdateRequestDto(
				List.of(canonical(FIRST_ID), canonical(SECOND_ID)), 4L);
		var owner = ownerResponse(userId, 5L);
		when(mutationService.prepareReplacement(
				userId, List.of(FIRST_ID, SECOND_ID), 4L))
				.thenReturn(new ListenerPlaylistMutationService.ReplacementPlan(
						true, List.of(first)));
		when(spotifyService.getPlaylistMetadataBatch(List.of(canonical(SECOND_ID))))
				.thenReturn(List.of(second));
		when(listenerProfileService.getMyProfile(userId)).thenReturn(owner);

		assertThat(service.replacePlaylists(userId, request)).isSameAs(owner);

		verify(mutationService).replace(userId, List.of(first, second), 4L);
	}

	@Test
	void upstreamFailureLeavesThePersistedListUntouched() {
		UUID userId = UUID.randomUUID();
		String url = canonical(FIRST_ID);
		var request = new ListenerPlaylistsUpdateRequestDto(List.of(url), 4L);
		when(mutationService.prepareReplacement(userId, List.of(FIRST_ID), 4L))
				.thenReturn(new ListenerPlaylistMutationService.ReplacementPlan(true, List.of()));
		when(spotifyService.getPlaylistMetadataBatch(List.of(url)))
				.thenThrow(new SoundConnectException(ErrorType.SPOTIFY_TIMEOUT));

		assertThatThrownBy(() -> service.replacePlaylists(userId, request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.SPOTIFY_TIMEOUT));

		verify(mutationService, org.mockito.Mockito.never())
				.replace(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
						org.mockito.ArgumentMatchers.anyLong());
		verifyNoInteractions(listenerProfileService);
	}

	@Test
	void serviceBoundaryRejectsMoreThanFourPlaylists() {
		UUID userId = UUID.randomUUID();
		var request = new ListenerPlaylistsUpdateRequestDto(
				List.of(canonical(FIRST_ID), canonical(SECOND_ID), canonical("1111111111111111111111"),
						canonical("2222222222222222222222"), canonical("3333333333333333333333")),
				0L
		);

		assertThatThrownBy(() -> service.replacePlaylists(userId, request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.VALIDATION_ERROR));
		verifyNoInteractions(spotifyService, mutationService, listenerProfileService);
	}

	private SpotifyPlaylistMetadataDto metadata(String id, String title) {
		return new SpotifyPlaylistMetadataDto(
				id,
				title,
				"https://i.scdn.co/image/" + id,
				canonical(id)
		);
	}

	private String canonical(String id) {
		return "https://open.spotify.com/playlist/" + id;
	}

	private ListenerProfileOwnerResponseDto ownerResponse(UUID userId, long version) {
		return new ListenerProfileOwnerResponseDto(
				UUID.randomUUID(), userId, "listener", ListenerVisibilityMode.STANDARD,
				true, version, null, "bio", null, null, 0L, 0L,
				true, true, true, true, List.of()
		);
	}
}
