package com.berkayb.soundconnect.modules.search.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileSearchServiceImplTest {
	@Mock MusicianProfileRepository musicianProfileRepository;
	@Mock ListenerProfileRepository listenerProfileRepository;
	@Mock BandRepository bandRepository;
	@Mock StudioProfileRepository studioProfileRepository;
	@Mock VenueRepository venueRepository;
	@Mock MediaAssetService mediaAssetService;
	@Mock ListenerVisibilityPolicy listenerVisibilityPolicy;
	@InjectMocks ProfileSearchServiceImpl service;

	@BeforeEach
	void emptyOtherProfileTypes() {
		lenient().when(listenerProfileRepository.searchForPublicDiscovery(
				anyString(), anyString(), eq(ListenerVisibilityMode.GHOST), any())).thenReturn(List.of());
		lenient().when(studioProfileRepository.searchByNameUsernameOrDescription(anyString(), anyString(), any())).thenReturn(List.of());
		lenient().when(venueRepository.searchByNameOrOwnerUsername(anyString(), anyString(), any()))
				.thenReturn(Page.empty());
		lenient().when(listenerVisibilityPolicy.publicVisibilityRestrictions(any()))
				.thenReturn(ListenerVisibilityPolicy.PublicVisibilityRestrictions.empty());
	}

	@Test
	void rejectsOversizedQueryBeforeAnyRepositoryCall() {
		assertThatThrownBy(() -> service.searchProfiles("x".repeat(101), 15))
				.isInstanceOf(SoundConnectException.class);

		verifyNoInteractions(
				musicianProfileRepository,
				listenerProfileRepository,
				bandRepository,
				studioProfileRepository,
				venueRepository,
				mediaAssetService,
				listenerVisibilityPolicy
		);
	}

	@Test
	void rankingUsesLocaleRootEvenWhenJvmDefaultLocaleIsTurkish() {
		User unrelatedUser = User.builder().id(UUID.randomUUID()).username("zeta").build();
		MusicianProfile unrelated = MusicianProfile.builder()
				.id(UUID.randomUUID())
				.user(unrelatedUser)
				.stageName("zeta")
				.build();
		Band prefixMatch = Band.builder().id(UUID.randomUUID()).name("Istanbul").build();
		when(musicianProfileRepository.searchByStageNameOrUsername(eq("IS"), eq("is"), any())).thenReturn(List.of(unrelated));
		when(bandRepository.searchByName(eq("IS"), any())).thenReturn(List.of(prefixMatch));

		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr-TR"));
			assertThat(service.searchProfiles("IS", 15))
					.first()
					.extracting(item -> item.title())
					.isEqualTo("Istanbul");
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	void repeatedSearchReadsCurrentUsernameWithoutKeepingHistory() {
		User user = User.builder().id(UUID.randomUUID()).username("oldname").build();
		MusicianProfile profile = MusicianProfile.builder()
				.id(UUID.randomUUID())
				.user(user)
				.build();
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString(), any()))
				.thenAnswer(invocation -> user.getUsername().contains(invocation.getArgument(1))
						? List.of(profile)
						: List.of());
		when(bandRepository.searchByName(anyString(), any())).thenReturn(List.of());

		assertThat(service.searchProfiles("oldname", 15))
				.singleElement()
				.satisfies(item -> assertThat(item.subtitle()).isEqualTo("oldname"));

		user.setUsername("newname");

		assertThat(service.searchProfiles("oldname", 15)).isEmpty();
		assertThat(service.searchProfiles("newname", 15))
				.singleElement()
				.satisfies(item -> {
					assertThat(item.title()).isEqualTo("newname");
					assertThat(item.subtitle()).isEqualTo("newname");
				});
	}

	@Test
	void usernameQueryUsesCanonicalBoundaryAndSimpleLowercaseContract() {
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString(), any()))
				.thenReturn(List.of());
		when(bandRepository.searchByName(anyString(), any())).thenReturn(List.of());

		service.searchProfiles("\uFEFF\u0130_USER%\u2003", 15);

		verify(musicianProfileRepository)
				.searchByStageNameOrUsername(eq("\u0130_USER%"), eq("i_user%"), any());
		verify(venueRepository).searchByNameOrOwnerUsername(
				org.mockito.ArgumentMatchers.eq("\u0130_USER%"),
				org.mockito.ArgumentMatchers.eq("i_user%"),
				any()
		);
	}

	@Test
	void boundaryWhitespaceOnlyQueryDoesNotSearchOrMatchEveryUsername() {
		assertThat(service.searchProfiles("\u00A0\u2003\uFEFF", 15)).isEmpty();

		verifyNoInteractions(
				musicianProfileRepository,
				listenerProfileRepository,
				bandRepository,
				studioProfileRepository,
				venueRepository,
				mediaAssetService,
				listenerVisibilityPolicy
		);
	}

	@Test
	void boundsEveryProfileTypeBeforeResultsReachApplicationMemory() {
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString(), any()))
				.thenReturn(List.of());
		when(bandRepository.searchByName(anyString(), any())).thenReturn(List.of());

		service.searchProfiles("test", 10_000);

		verify(musicianProfileRepository).searchByStageNameOrUsername(
				eq("test"),
				eq("test"),
				argThat(page -> page.getPageNumber() == 0 && page.getPageSize() == 30)
		);
		verify(listenerProfileRepository).searchForPublicDiscovery(
				eq("test"),
				eq("test"),
				eq(ListenerVisibilityMode.GHOST),
				argThat(page -> page.getPageNumber() == 0 && page.getPageSize() == 30)
		);
		verify(bandRepository).searchByName(
				eq("test"),
				argThat(page -> page.getPageNumber() == 0 && page.getPageSize() == 30)
		);
		verify(studioProfileRepository).searchByNameUsernameOrDescription(
				eq("test"),
				eq("test"),
				argThat(page -> page.getPageNumber() == 0 && page.getPageSize() == 30)
		);
	}

	@Test
	void resolvesMediaOnlyForCandidatesThatSurviveTheGlobalLimit() {
		UUID selectedMediaId = UUID.randomUUID();
		UUID discardedMediaId = UUID.randomUUID();
		User selectedUser = User.builder().id(UUID.randomUUID()).username("selected").build();
		User discardedUser = User.builder().id(UUID.randomUUID()).username("discarded").build();
		MusicianProfile selected = MusicianProfile.builder()
				.id(UUID.randomUUID())
				.user(selectedUser)
				.stageName("test")
				.profilePictureMediaId(selectedMediaId)
				.build();
		MusicianProfile discarded = MusicianProfile.builder()
				.id(UUID.randomUUID())
				.user(discardedUser)
				.stageName("test two")
				.profilePictureMediaId(discardedMediaId)
				.build();
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString(), any()))
				.thenReturn(List.of(selected, discarded));
		when(bandRepository.searchByName(anyString(), any())).thenReturn(List.of());
		when(mediaAssetService.getDisplayUrlMap(List.of(selectedMediaId)))
				.thenReturn(Map.of(selectedMediaId, "https://cdn.example/selected.jpg"));

		assertThat(service.searchProfiles("test", 1))
				.singleElement()
				.satisfies(item -> assertThat(item.imageUrl()).isEqualTo("https://cdn.example/selected.jpg"));

		verify(mediaAssetService).getDisplayUrlMap(List.of(selectedMediaId));
		verifyNoMoreInteractions(mediaAssetService);
	}

	@Test
	void studioSearchSubtitleUsesLocationInsteadOfDescription() {
		City city = City.builder().name("İstanbul").build();
		District district = District.builder().name("Kadıköy").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder()
				.name("Caferağa")
				.district(district)
				.build();
		User owner = User.builder().id(UUID.randomUUID()).username("studio-owner").build();
		StudioProfile studio = StudioProfile.builder()
				.id(UUID.randomUUID())
				.user(owner)
				.name("test studio")
				.description("Arama sonucunda gösterilmemeli")
				.city(city)
				.district(district)
				.neighborhood(neighborhood)
				.build();
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString(), any()))
				.thenReturn(List.of());
		when(bandRepository.searchByName(anyString(), any())).thenReturn(List.of());
		when(studioProfileRepository.searchByNameUsernameOrDescription(eq("test"), eq("test"), any()))
				.thenReturn(List.of(studio));

		assertThat(service.searchProfiles("test", 15))
				.singleElement()
				.satisfies(item -> {
					assertThat(item.type()).isEqualTo("STUDIO");
					assertThat(item.subtitle()).isEqualTo("Caferağa, Kadıköy, İstanbul");
					assertThat(item.subtitle()).doesNotContain(studio.getDescription());
				});
	}

	@Test
	void searchKeepsPessimisticVisibilityReadLocksInAWriteCapableTransaction() throws Exception {
		Transactional transaction = ProfileSearchServiceImpl.class
				.getMethod("searchProfiles", String.class, int.class)
				.getAnnotation(Transactional.class);

		assertThat(transaction).isNotNull();
		assertThat(transaction.readOnly()).isFalse();
	}

	@Test
	void ghostListenerSearchReturnsOnlyContextualIdentityAndVisibilityMarker() {
		UUID mediaId = UUID.randomUUID();
		User user = User.builder().id(UUID.randomUUID()).username("ghostlistener").build();
		ListenerProfile profile = ListenerProfile.builder()
				.id(UUID.randomUUID())
				.user(user)
				.name("Hidden Display Name")
				.description("Hidden biography")
				.profilePictureMediaId(mediaId)
				.visibilityMode(ListenerVisibilityMode.GHOST)
				.build();
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString(), any()))
				.thenReturn(List.of());
		when(bandRepository.searchByName(anyString(), any())).thenReturn(List.of());
		when(listenerProfileRepository.searchForPublicDiscovery(
				eq("ghostlistener"),
				eq("ghostlistener"),
				eq(ListenerVisibilityMode.GHOST),
				any()
		)).thenReturn(List.of(profile));
		when(mediaAssetService.getDisplayUrlMap(List.of(mediaId)))
				.thenReturn(Map.of(mediaId, "https://cdn.example/ghost-avatar.jpg"));

		assertThat(service.searchProfiles("ghostlistener", 15))
				.singleElement()
				.satisfies(item -> {
					assertThat(item.type()).isEqualTo("LISTENER");
					assertThat(item.title()).isEqualTo("ghostlistener");
					assertThat(item.subtitle()).isNull();
					assertThat(item.imageUrl()).isEqualTo("https://cdn.example/ghost-avatar.jpg");
					assertThat(item.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
				});
	}

	@Test
	void listenerThatTurnsGhostAfterCandidateQueryIsReprojectedBeforeResponse() {
		UUID userId = UUID.randomUUID();
		User user = User.builder().id(userId).username("canonical_listener").build();
		ListenerProfile profile = ListenerProfile.builder()
				.id(UUID.randomUUID())
				.user(user)
				.name("Private Display Name")
				.description("Private biography")
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.build();
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString(), any()))
				.thenReturn(List.of());
		when(bandRepository.searchByName(anyString(), any())).thenReturn(List.of());
		when(listenerProfileRepository.searchForPublicDiscovery(
				anyString(), anyString(), eq(ListenerVisibilityMode.GHOST), any()))
				.thenReturn(List.of(profile));
		when(listenerVisibilityPolicy.publicVisibilityRestrictions(any()))
				.thenReturn(new ListenerVisibilityPolicy.PublicVisibilityRestrictions(
						Set.of(userId), Set.of()));

		assertThat(service.searchProfiles("canonical", 15))
				.singleElement()
				.satisfies(item -> {
					assertThat(item.type()).isEqualTo("LISTENER");
					assertThat(item.title()).isEqualTo("canonical_listener");
					assertThat(item.subtitle()).isNull();
					assertThat(item.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
					assertThat(item.title()).doesNotContain("Private Display Name");
				});
	}

	@Test
	void mediaBatchFailureKeepsFinalGhostProjectionAndReturnsNullImage() {
		UUID userId = UUID.randomUUID();
		UUID mediaId = UUID.randomUUID();
		User user = User.builder().id(userId).username("canonical_listener").build();
		ListenerProfile profile = ListenerProfile.builder()
				.id(UUID.randomUUID())
				.user(user)
				.name("Private Display Name")
				.profilePictureMediaId(mediaId)
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.build();
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString(), any()))
				.thenReturn(List.of());
		when(bandRepository.searchByName(anyString(), any())).thenReturn(List.of());
		when(listenerProfileRepository.searchForPublicDiscovery(
				anyString(), anyString(), eq(ListenerVisibilityMode.GHOST), any()))
				.thenReturn(List.of(profile));
		when(listenerVisibilityPolicy.publicVisibilityRestrictions(any()))
				.thenReturn(new ListenerVisibilityPolicy.PublicVisibilityRestrictions(
						Set.of(userId), Set.of()));
		when(mediaAssetService.getDisplayUrlMap(List.of(mediaId)))
				.thenThrow(new IllegalStateException("media store unavailable"));

		assertThat(service.searchProfiles("canonical", 15))
				.singleElement()
				.satisfies(item -> {
					assertThat(item.title()).isEqualTo("canonical_listener");
					assertThat(item.subtitle()).isNull();
					assertThat(item.visibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
					assertThat(item.imageUrl()).isNull();
				});
	}

	@Test
	void corruptPersonalProfilesOwnedByGhostListenerFailClosedInOneBatch() {
		UUID userId = UUID.randomUUID();
		User user = User.builder().id(userId).username("ghost-shared").build();
		MusicianProfile musician = MusicianProfile.builder()
				.id(UUID.randomUUID()).user(user).stageName("ghost musician").build();
		ListenerProfile listener = ListenerProfile.builder()
				.id(UUID.randomUUID()).user(user).name("Hidden Listener Name")
				.visibilityMode(ListenerVisibilityMode.GHOST).build();
		StudioProfile studio = StudioProfile.builder()
				.id(UUID.randomUUID()).user(user).name("ghost studio").build();
		Venue venue = Venue.builder()
				.id(UUID.randomUUID()).owner(user).name("ghost venue").build();
		Band band = Band.builder().id(UUID.randomUUID()).name("ghost band").build();

		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString(), any()))
				.thenReturn(List.of(musician));
		when(listenerProfileRepository.searchForPublicDiscovery(
				anyString(), anyString(), eq(ListenerVisibilityMode.GHOST), any()))
				.thenReturn(List.of(listener));
		when(bandRepository.searchByName(anyString(), any())).thenReturn(List.of(band));
		when(studioProfileRepository.searchByNameUsernameOrDescription(anyString(), anyString(), any()))
				.thenReturn(List.of(studio));
		when(venueRepository.searchByNameOrOwnerUsername(anyString(), anyString(), any()))
				.thenReturn(new PageImpl<>(List.of(venue)));
		when(listenerVisibilityPolicy.publicVisibilityRestrictions(any()))
				.thenReturn(new ListenerVisibilityPolicy.PublicVisibilityRestrictions(
						Set.of(userId), Set.of()));

		assertThat(service.searchProfiles("ghost", 15))
				.extracting(item -> item.type())
				.containsExactlyInAnyOrder("LISTENER", "BAND");
		verify(listenerVisibilityPolicy).publicVisibilityRestrictions(argThat(ids ->
				ids.stream().filter(userId::equals).count() == 4));
	}

	@Test
	void pendingListenerChoiceSuppressesListenerAndCorruptCrossTypeResults() {
		UUID userId = UUID.randomUUID();
		User user = User.builder().id(userId).username("pending_listener").build();
		ListenerProfile listener = ListenerProfile.builder()
				.id(UUID.randomUUID())
				.user(user)
				.name("Must stay hidden")
				.visibilityMode(ListenerVisibilityMode.STANDARD)
				.visibilityChoiceCompleted(false)
				.build();
		MusicianProfile corruptMusician = MusicianProfile.builder()
				.id(UUID.randomUUID()).user(user).stageName("Must also stay hidden").build();
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString(), any()))
				.thenReturn(List.of(corruptMusician));
		when(listenerProfileRepository.searchForPublicDiscovery(
				anyString(), anyString(), eq(ListenerVisibilityMode.GHOST), any()))
				.thenReturn(List.of(listener));
		when(bandRepository.searchByName(anyString(), any())).thenReturn(List.of());
		when(listenerVisibilityPolicy.publicVisibilityRestrictions(any()))
				.thenReturn(new ListenerVisibilityPolicy.PublicVisibilityRestrictions(
						Set.of(), Set.of(userId)));

		assertThat(service.searchProfiles("hidden", 15)).isEmpty();
		verifyNoInteractions(mediaAssetService);
	}
}
