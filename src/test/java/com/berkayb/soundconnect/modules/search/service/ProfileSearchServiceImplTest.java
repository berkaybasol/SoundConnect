package com.berkayb.soundconnect.modules.search.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileSearchServiceImplTest {
	@Mock MusicianProfileRepository musicianProfileRepository;
	@Mock ListenerProfileRepository listenerProfileRepository;
	@Mock BandRepository bandRepository;
	@Mock StudioProfileRepository studioProfileRepository;
	@Mock VenueRepository venueRepository;
	@Mock MediaAssetService mediaAssetService;
	@InjectMocks ProfileSearchServiceImpl service;

	@BeforeEach
	void emptyOtherProfileTypes() {
		lenient().when(listenerProfileRepository.searchByUsernameOrBio(anyString(), anyString())).thenReturn(List.of());
		lenient().when(studioProfileRepository.searchByNameUsernameOrDescription(anyString(), anyString())).thenReturn(List.of());
		lenient().when(venueRepository.searchByNameOrOwnerUsername(anyString(), anyString(), any()))
				.thenReturn(Page.empty());
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
		when(musicianProfileRepository.searchByStageNameOrUsername("IS", "is")).thenReturn(List.of(unrelated));
		when(bandRepository.searchByName("IS")).thenReturn(List.of(prefixMatch));

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
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString()))
				.thenAnswer(invocation -> user.getUsername().contains(invocation.getArgument(1))
						? List.of(profile)
						: List.of());
		when(bandRepository.searchByName(anyString())).thenReturn(List.of());

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
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString()))
				.thenReturn(List.of());
		when(bandRepository.searchByName(anyString())).thenReturn(List.of());

		service.searchProfiles("\uFEFF\u0130_USER%\u2003", 15);

		verify(musicianProfileRepository)
				.searchByStageNameOrUsername("\u0130_USER%", "i_user%");
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
				mediaAssetService
		);
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
		when(musicianProfileRepository.searchByStageNameOrUsername(anyString(), anyString()))
				.thenReturn(List.of());
		when(bandRepository.searchByName(anyString())).thenReturn(List.of());
		when(studioProfileRepository.searchByNameUsernameOrDescription("test", "test"))
				.thenReturn(List.of(studio));

		assertThat(service.searchProfiles("test", 15))
				.singleElement()
				.satisfies(item -> {
					assertThat(item.type()).isEqualTo("STUDIO");
					assertThat(item.subtitle()).isEqualTo("Caferağa, Kadıköy, İstanbul");
					assertThat(item.subtitle()).doesNotContain(studio.getDescription());
				});
	}
}
