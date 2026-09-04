package com.berkayb.soundconnect.modules.profile.ListenerProfile.service;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerSpotifyPlaylist;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerSpotifyPlaylistRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerProfileProvisioner;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyPlaylistMetadataDto;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListenerPlaylistMutationServiceTest {

	@Mock ListenerProfileRepository profileRepository;
	@Mock ListenerSpotifyPlaylistRepository playlistRepository;
	@Mock UserEntityFinder userEntityFinder;
	@Mock ListenerProfileProvisioner profileProvisioner;
	@InjectMocks ListenerPlaylistMutationService service;

	private UUID userId;
	private ListenerProfile profile;

	@BeforeEach
	void setUp() {
		userId = UUID.randomUUID();
		User user = User.builder().id(userId).username("listener").password("secret").build();
		profile = ListenerProfile.builder()
				.id(UUID.randomUUID())
				.user(user)
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(true)
				.version(4L)
				.playlistRevision(2L)
				.build();
	}

	@Test
	void replacementAdvancesTheParentMutationMarkerAndPersistsOrderedSnapshots() {
		stubProfile();
		when(playlistRepository.findSpotifyPlaylistIdsByListenerProfileId(profile.getId()))
				.thenReturn(List.of("37i9dQZF1DXcBWIGoYBM5M"));
		when(profileRepository.saveAndFlush(profile)).thenAnswer(invocation -> {
			profile.setVersion(5L);
			return profile;
		});
		List<SpotifyPlaylistMetadataDto> metadata = List.of(
				metadata("0vvXsWCC9xrXsKd4FyS8kM", "Second"),
				metadata("1111111111111111111111", "Third")
		);

		service.replace(userId, metadata, 4L);

		assertThat(profile.getPlaylistRevision()).isEqualTo(3L);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ListenerSpotifyPlaylist>> captor = ArgumentCaptor.forClass(List.class);
		verify(playlistRepository).saveAll(captor.capture());
		assertThat(captor.getValue())
				.extracting(ListenerSpotifyPlaylist::getPosition)
				.containsExactly(0, 1);
		assertThat(captor.getValue())
				.extracting(ListenerSpotifyPlaylist::getSpotifyPlaylistId)
				.containsExactly("0vvXsWCC9xrXsKd4FyS8kM", "1111111111111111111111");
		InOrder order = inOrder(playlistRepository, profileRepository);
		order.verify(playlistRepository).deleteAllByListenerProfileId(profile.getId());
		order.verify(playlistRepository).flush();
		order.verify(playlistRepository).saveAll(any());
		order.verify(profileRepository).saveAndFlush(profile);
	}

	@Test
	void exactRetryIsSuccessfulEvenWithTheOriginalVersionAndDoesNotMutate() {
		stubProfile();
		when(playlistRepository.findSpotifyPlaylistIdsByListenerProfileId(profile.getId()))
				.thenReturn(List.of("37i9dQZF1DXcBWIGoYBM5M"));

		service.replace(userId, List.of(metadata("37i9dQZF1DXcBWIGoYBM5M", "Same")), 1L);

		assertThat(profile.getPlaylistRevision()).isEqualTo(2L);
		verify(playlistRepository, never()).deleteAllByListenerProfileId(any());
		verify(profileRepository, never()).saveAndFlush(any());
	}

	@Test
	void ghostProfileIsLockedBeforeCurrentPlaylistDataIsRead() {
		profile.setVisibilityMode(ListenerVisibilityMode.GHOST);
		stubProfile();

		assertThatThrownBy(() -> service.prepareReplacement(
				userId, List.of("37i9dQZF1DXcBWIGoYBM5M"), 4L))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.LISTENER_PROFILE_CONTENT_LOCKED));

		verifyNoInteractions(playlistRepository);
	}

	@Test
	void preparationReturnsReusableSnapshotsForAChangedList() {
		stubProfile();
		var existingMetadata = metadata("37i9dQZF1DXcBWIGoYBM5M", "Existing");
		var existing = ListenerSpotifyPlaylist.create(profile, existingMetadata, 0);
		when(playlistRepository.findAllByListenerProfileIdOrderByPositionAsc(profile.getId()))
				.thenReturn(List.of(existing));

		var plan = service.prepareReplacement(
				userId, List.of("0vvXsWCC9xrXsKd4FyS8kM"), 4L);

		assertThat(plan.replacementRequired()).isTrue();
		assertThat(plan.reusableMetadata()).containsExactly(existingMetadata);
	}

	@Test
	void preparationRecognizesAnExactRetryBeforeCheckingItsStaleVersion() {
		stubProfile();
		var existingMetadata = metadata("37i9dQZF1DXcBWIGoYBM5M", "Existing");
		var existing = ListenerSpotifyPlaylist.create(profile, existingMetadata, 0);
		when(playlistRepository.findAllByListenerProfileIdOrderByPositionAsc(profile.getId()))
				.thenReturn(List.of(existing));

		var plan = service.prepareReplacement(
				userId, List.of("37i9dQZF1DXcBWIGoYBM5M"), 1L);

		assertThat(plan.replacementRequired()).isFalse();
		assertThat(plan.reusableMetadata()).isEmpty();
	}

	@Test
	void staleDifferentReplacementCannotDeleteTheCurrentList() {
		stubProfile();
		when(playlistRepository.findSpotifyPlaylistIdsByListenerProfileId(profile.getId()))
				.thenReturn(List.of("37i9dQZF1DXcBWIGoYBM5M"));

		assertThatThrownBy(() -> service.replace(
				userId,
				List.of(metadata("0vvXsWCC9xrXsKd4FyS8kM", "New")),
				3L))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.LISTENER_PROFILE_VERSION_CONFLICT));

		verify(playlistRepository, never()).deleteAllByListenerProfileId(any());
		verify(profileRepository, never()).saveAndFlush(any());
	}

	private void stubProfile() {
		when(profileRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(profile));
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
