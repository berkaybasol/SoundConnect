package com.berkayb.soundconnect.tools.simulation.seed.profile;

import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesResponse;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesUpdate;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.OpportunityCitySummary;
import com.berkayb.soundconnect.modules.feed.musician.preference.service.MusicianFeedPreferencesService;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerAvatarUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerVisibilityUpdateRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileOwnerResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.seed.profile.SimulationProfilePictureResult.Outcome;
import com.berkayb.soundconnect.tools.simulation.seed.profile.SimulationProfileReference.State;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationLocationResolver;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationResolvedLocation;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.ListenerVisibilityState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.MusicianProfilePlan;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationProfileMaterializerTest {
	@Mock private SimulationRuntimeGuard runtimeGuard;
	@Mock private Validator validator;
	@Mock private SimulationLocationResolver locationResolver;
	@Mock private InstrumentRepository instrumentRepository;
	@Mock private MusicianProfileService musicianProfileService;
	@Mock private MusicianFeedPreferencesService musicianFeedPreferencesService;
	@Mock private SimulationMusicianLegacyStageNameCleaner musicianStageNameCleaner;
	@Mock private ListenerProfileService listenerProfileService;
	@Mock private VenueProfileService venueProfileService;
	@Mock private StudioProfileService studioProfileService;

	private SimulationProfileMaterializer materializer;
	private SimulationResolvedLocation location;

	@BeforeEach
	void setUp() {
		materializer = new SimulationProfileMaterializer(
				runtimeGuard, validator, locationResolver, instrumentRepository,
				musicianProfileService, musicianFeedPreferencesService, musicianStageNameCleaner,
				listenerProfileService,
				venueProfileService, studioProfileService);
		City city = City.builder().id(UUID.randomUUID()).name("İstanbul").build();
		District district = District.builder().id(UUID.randomUUID()).name("Kadıköy").city(city).build();
		Neighborhood neighborhood = Neighborhood.builder()
				.id(UUID.randomUUID()).name("Caferağa").district(district).build();
		location = new SimulationResolvedLocation(city, district, neighborhood, "Nota Sok. No: 8");
	}

	@Test
	void materializesVerifiedMusicianThroughProfileAndPreferenceServices() {
		Account account = musician("musician-one", EmailVerificationState.VERIFIED,
				new MusicianProfilePlan(true, true, true, true, true, true), List.of("Elektro Gitar"));
		UUID userId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		UUID instrumentId = UUID.randomUUID();
		Instrument instrument = Instrument.builder().name("Elektro Gitar").build();
		instrument.setId(instrumentId);
		when(musicianProfileService.getProfileByUserId(userId))
				.thenReturn(musicianResponse(profileId, userId, null, null, Set.of(), null, null));
		when(instrumentRepository.findByNameIgnoreCase("Elektro Gitar")).thenReturn(Optional.of(instrument));
		when(musicianProfileService.updateProfile(eq(userId), any()))
				.thenReturn(musicianResponse(
						profileId, userId, null, account.bio(),
						Set.of("Elektro Gitar"), "https://www.instagram.com/" + account.username(), null));
		when(locationResolver.resolve(account.location())).thenReturn(location);
		when(musicianFeedPreferencesService.get(userId))
				.thenReturn(new MusicianFeedPreferencesResponse(1, 3L, null, List.of(), null));
		when(musicianFeedPreferencesService.update(eq(userId), any()))
				.thenReturn(new MusicianFeedPreferencesResponse(
						1, 4L, new OpportunityCitySummary(location.city().getId(), "İstanbul"), List.of(), null));

		SimulationProfileMaterializationResult result = materializer.materialize(
				manifest(List.of(account)), Map.of(account.key(), userId));

		assertThat(result.require(account.key()).profileId()).isEqualTo(profileId);
		assertThat(result.require(account.key()).state()).isEqualTo(State.MATERIALIZED);
		ArgumentCaptor<MusicianProfileSaveRequestDto> profileUpdate =
				ArgumentCaptor.forClass(MusicianProfileSaveRequestDto.class);
		verify(musicianProfileService).updateProfile(eq(userId), profileUpdate.capture());
		assertThat(profileUpdate.getValue().instrumentIds()).containsExactly(instrumentId);
		assertThat(profileUpdate.getValue().stageName()).isNull();
		verify(musicianStageNameCleaner).clearForUserIds(Set.of(userId));
		ArgumentCaptor<MusicianFeedPreferencesUpdate> preferenceUpdate =
				ArgumentCaptor.forClass(MusicianFeedPreferencesUpdate.class);
		verify(musicianFeedPreferencesService).update(eq(userId), preferenceUpdate.capture());
		assertThat(preferenceUpdate.getValue())
				.isEqualTo(new MusicianFeedPreferencesUpdate(location.city().getId(), 3L));
		verify(runtimeGuard).assertRuntimeAllowed();
	}

	@Test
	void preservesUnverifiedMusicianAsANegativeControl() {
		Account account = musician("musician-unverified", EmailVerificationState.UNVERIFIED,
				new MusicianProfilePlan(true, true, true, true, true, true), List.of("Vokal"));
		UUID userId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		when(musicianProfileService.getProfileByUserId(userId))
				.thenReturn(musicianResponse(profileId, userId, null, null, Set.of(), null, null));

		SimulationProfileReference result = materializer.materialize(
				manifest(List.of(account)), Map.of(account.key(), userId)).require(account.key());

		assertThat(result.state()).isEqualTo(State.UNVERIFIED_CONTROL);
		assertThat(result.profileId()).isEqualTo(profileId);
		verify(musicianStageNameCleaner).clearForUserIds(Set.of(userId));
		verify(musicianProfileService, never()).updateProfile(any(), any());
		verify(musicianFeedPreferencesService, never()).get(any());
		verify(locationResolver, never()).resolve(any());
	}

	@Test
	void appliesListenerVisibilityAndBioWhileLeavingPendingControlUntouched() {
		Account standard = listener("listener-standard", ListenerVisibilityState.STANDARD);
		Account ghost = listener("listener-ghost", ListenerVisibilityState.GHOST);
		Account pending = listener("listener-pending", ListenerVisibilityState.VISIBILITY_PENDING);
		UUID standardUser = UUID.randomUUID();
		UUID ghostUser = UUID.randomUUID();
		UUID pendingUser = UUID.randomUUID();
		UUID standardProfile = UUID.randomUUID();
		UUID ghostProfile = UUID.randomUUID();
		UUID pendingProfile = UUID.randomUUID();
		when(listenerProfileService.getMyProfile(standardUser))
				.thenReturn(listenerResponse(standardProfile, standardUser, ListenerVisibilityMode.STANDARD, false, 0, null, null));
		when(listenerProfileService.updateVisibility(eq(standardUser), any()))
				.thenReturn(listenerResponse(standardProfile, standardUser, ListenerVisibilityMode.STANDARD, true, 1, null, null));
		when(listenerProfileService.updateMyProfile(eq(standardUser), any()))
				.thenReturn(listenerResponse(standardProfile, standardUser, ListenerVisibilityMode.STANDARD, true, 2, standard.bio(), null));
		when(listenerProfileService.getMyProfile(ghostUser))
				.thenReturn(listenerResponse(ghostProfile, ghostUser, ListenerVisibilityMode.STANDARD, false, 0, null, null));
		when(listenerProfileService.updateVisibility(eq(ghostUser), any()))
				.thenReturn(listenerResponse(ghostProfile, ghostUser, ListenerVisibilityMode.GHOST, true, 1, null, null));
		when(listenerProfileService.getMyProfile(pendingUser))
				.thenReturn(listenerResponse(pendingProfile, pendingUser, ListenerVisibilityMode.STANDARD, false, 0, null, null));
		Map<String, UUID> ids = linkedIds(
				standard.key(), standardUser, ghost.key(), ghostUser, pending.key(), pendingUser);

		SimulationProfileMaterializationResult result = materializer.materialize(
				manifest(List.of(standard, ghost, pending)), ids);

		assertThat(result.require(standard.key()).state()).isEqualTo(State.MATERIALIZED);
		assertThat(result.require(ghost.key()).state()).isEqualTo(State.MATERIALIZED);
		assertThat(result.require(pending.key()).state()).isEqualTo(State.VISIBILITY_PENDING_CONTROL);
		verify(listenerProfileService).updateMyProfile(eq(standardUser), any(ListenerSaveRequestDto.class));
		verify(listenerProfileService, never()).updateMyProfile(eq(ghostUser), any());
		verify(listenerProfileService, never()).updateVisibility(eq(pendingUser), any());
	}

	@Test
	void returnsVenueProfileAndAggregateIdsAndSkipsInstitutionControls() {
		Account venue = institution("venue-approved", AccountRole.VENUE, InstitutionState.APPROVED);
		Account venuePending = institution("venue-pending", AccountRole.VENUE, InstitutionState.PENDING);
		Account studio = institution("studio-approved", AccountRole.STUDIO, InstitutionState.APPROVED);
		Account studioRejected = institution("studio-rejected", AccountRole.STUDIO, InstitutionState.REJECTED);
		UUID venueUser = UUID.randomUUID();
		UUID pendingUser = UUID.randomUUID();
		UUID studioUser = UUID.randomUUID();
		UUID rejectedUser = UUID.randomUUID();
		UUID venueProfileId = UUID.randomUUID();
		UUID venueId = UUID.randomUUID();
		UUID studioProfileId = UUID.randomUUID();
		VenueProfileResponseDto venueCurrent = new VenueProfileResponseDto(
				venueProfileId, venueId, venue.displayName(), null, null, null, null, null);
		VenueProfileResponseDto venueUpdated = new VenueProfileResponseDto(
				venueProfileId, venueId, venue.displayName(), venue.bio(), null,
				"https://www.instagram.com/" + venue.username(), null,
				"https://soundconnect.invalid/profiles/" + venue.username());
		when(venueProfileService.getProfilesByUserId(venueUser)).thenReturn(List.of(venueCurrent));
		when(venueProfileService.updateProfileByVenueId(eq(venueUser), eq(venueId), any()))
				.thenReturn(venueUpdated);
		when(studioProfileService.getProfileByUserId(studioUser))
				.thenReturn(studioResponse(studioProfileId, studioUser, studio.displayName(), null, null,
						Set.of(), null, null, 2));
		when(studioProfileService.updateProfile(eq(studioUser), any()))
				.thenReturn(studioResponse(
						studioProfileId, studioUser, studio.displayName(), studio.bio(), null,
						Set.of("Kayıt Odası", "Prova Odası", "Canlı Kayıt"),
						"https://soundconnect.invalid/profiles/" + studio.username(),
						"https://www.instagram.com/" + studio.username(), 3));
		Map<String, UUID> ids = linkedIds(
				venue.key(), venueUser, venuePending.key(), pendingUser,
				studio.key(), studioUser, studioRejected.key(), rejectedUser);

		SimulationProfileMaterializationResult result = materializer.materialize(
				manifest(List.of(venue, venuePending, studio, studioRejected)), ids);

		assertThat(result.require(venue.key()).profileId()).isEqualTo(venueProfileId);
		assertThat(result.require(venue.key()).venueAggregateId()).isEqualTo(venueId);
		assertThat(result.require(studio.key()).profileId()).isEqualTo(studioProfileId);
		assertThat(result.require(venuePending.key()).state()).isEqualTo(State.INSTITUTION_PENDING_CONTROL);
		assertThat(result.require(studioRejected.key()).state()).isEqualTo(State.INSTITUTION_REJECTED_CONTROL);
		verify(venueProfileService, never()).getProfilesByUserId(pendingUser);
		verify(studioProfileService, never()).getProfileByUserId(rejectedUser);
	}

	@Test
	void secondPhaseUsesVersionedListenerAvatarServiceAndIsIdempotent() {
		Account listener = listener("listener-avatar", ListenerVisibilityState.STANDARD);
		UUID userId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		UUID mediaId = UUID.randomUUID();
		SimulationProfileMaterializationResult profiles = profileResult(
				listener, userId, profileId, State.MATERIALIZED);
		when(listenerProfileService.getMyProfile(userId))
				.thenReturn(listenerResponse(profileId, userId, ListenerVisibilityMode.STANDARD, true, 7, listener.bio(), null));
		when(listenerProfileService.updateAvatar(eq(userId), any()))
				.thenReturn(listenerResponse(profileId, userId, ListenerVisibilityMode.STANDARD, true, 8, listener.bio(), mediaId));

		SimulationProfilePictureResult result = materializer.applyProfilePictures(
				manifest(List.of(listener)), Map.of(listener.key(), userId), profiles,
				Map.of(listener.key(), mediaId));

		assertThat(result.outcomesByAccountKey()).containsEntry(listener.key(), Outcome.APPLIED);
		ArgumentCaptor<ListenerAvatarUpdateRequestDto> update =
				ArgumentCaptor.forClass(ListenerAvatarUpdateRequestDto.class);
		verify(listenerProfileService).updateAvatar(eq(userId), update.capture());
		assertThat(update.getValue().profilePictureMediaId()).isEqualTo(mediaId);
		assertThat(update.getValue().expectedVersion()).isEqualTo(7L);
	}

	@Test
	void secondPhaseSkipsIncompleteMusicianPictureWithoutReadingProfile() {
		Account musician = musician("musician-incomplete", EmailVerificationState.VERIFIED,
				new MusicianProfilePlan(true, true, false, false, false, false), List.of());
		UUID userId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		SimulationProfileMaterializationResult profiles = profileResult(
				musician, userId, profileId, State.MATERIALIZED);

		SimulationProfilePictureResult result = materializer.applyProfilePictures(
				manifest(List.of(musician)), Map.of(musician.key(), userId), profiles,
				Map.of(musician.key(), UUID.randomUUID()));

		assertThat(result.outcomesByAccountKey()).containsEntry(
				musician.key(), Outcome.INELIGIBLE_CONTROL);
		verify(musicianProfileService, never()).getProfileByUserId(any());
		verify(musicianProfileService, never()).updateProfile(any(), any());
	}

	@Test
	void explicitJakartaValidationStopsProfileMutation() {
		Account account = musician("musician-invalid", EmailVerificationState.VERIFIED,
				new MusicianProfilePlan(true, false, false, false, false, false), List.of());
		UUID userId = UUID.randomUUID();
		when(musicianProfileService.getProfileByUserId(userId)).thenReturn(
				musicianResponse(UUID.randomUUID(), userId, null, null, Set.of(), null, null));
		@SuppressWarnings("unchecked")
		ConstraintViolation<MusicianProfileSaveRequestDto> violation =
				org.mockito.Mockito.mock(ConstraintViolation.class);
		when(validator.validate(any(MusicianProfileSaveRequestDto.class))).thenReturn(Set.of(violation));

		assertThatThrownBy(() -> materializer.materialize(
				manifest(List.of(account)), Map.of(account.key(), userId)))
				.isInstanceOf(ConstraintViolationException.class)
				.hasMessageContaining(account.key());
		verify(musicianProfileService, never()).updateProfile(any(), any());
	}

	private SimulationProfileMaterializationResult profileResult(
			Account account, UUID userId, UUID profileId, State state) {
		return new SimulationProfileMaterializationResult(Map.of(
				account.key(), new SimulationProfileReference(
						account.key(), account.role(), userId, profileId, null, state)));
	}

	private Account musician(
			String key,
			EmailVerificationState verification,
			MusicianProfilePlan plan,
			List<String> instruments
	) {
		return new Account(
				key, AccountRole.MUSICIAN, SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK,
				key, key + "@soundconnect.invalid", verification, "Deniz", "Test",
				"Deniz Test", "Üretime açık yerel simülasyon müzisyeni.", "Test müzisyeni",
				instruments, plan, SimulationWorldManifest.ObserverProfile.NONE,
				null, null, locationManifest(), null);
	}

	private Account listener(String key, ListenerVisibilityState visibility) {
		return new Account(
				key, AccountRole.LISTENER, SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK,
				key, key + "@soundconnect.invalid", EmailVerificationState.VERIFIED,
				"Ece", "Test", "Ece Test", "Yerel sahneyi yakından takip eden dinleyici.",
				"Test dinleyicisi", List.of(), null, SimulationWorldManifest.ObserverProfile.NONE,
				visibility, null, locationManifest(), null);
	}

	private Account institution(String key, AccountRole role, InstitutionState state) {
		return new Account(
				key, role, SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK,
				key, key + "@soundconnect.invalid", EmailVerificationState.VERIFIED,
				"Test", "İşletme", role == AccountRole.VENUE ? "Nota Sahne" : "Nota Stüdyo",
				"Yerel sanatçılara açık, güvenli ve nitelikli bir üretim alanı.",
				"Test işletmesi", List.of(), null, SimulationWorldManifest.ObserverProfile.NONE,
				null, state, locationManifest(), "05550000001");
	}

	private SimulationWorldManifest.Location locationManifest() {
		return new SimulationWorldManifest.Location("İstanbul", "Kadıköy", "Caferağa", "Nota Sok. No: 8");
	}

	private SimulationWorldManifest manifest(List<Account> accounts) {
		return new SimulationWorldManifest(1, "test-world", 42L, List.of(),
				new SimulationWorldManifest.ContentTargets(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
				accounts, List.of());
	}

	private Map<String, UUID> linkedIds(Object... values) {
		LinkedHashMap<String, UUID> result = new LinkedHashMap<>();
		for (int index = 0; index < values.length; index += 2) {
			result.put((String) values[index], (UUID) values[index + 1]);
		}
		return result;
	}

	private MusicianProfileResponseDto musicianResponse(
			UUID profileId,
			UUID userId,
			String stageName,
			String bio,
			Set<String> instruments,
			String instagram,
			UUID pictureId
	) {
		return new MusicianProfileResponseDto(
				profileId, userId, "musician", stageName, bio, pictureId, null,
				instagram, null, null, null, null, instruments, Set.of(), Set.of(), List.of(), List.of());
	}

	private ListenerProfileOwnerResponseDto listenerResponse(
			UUID profileId,
			UUID userId,
			ListenerVisibilityMode mode,
			boolean completed,
			long version,
			String bio,
			UUID pictureId
	) {
		return new ListenerProfileOwnerResponseDto(
				profileId, userId, "listener", mode, completed, version, Instant.EPOCH,
				bio, pictureId, null, 0L, 0L,
				completed && mode == ListenerVisibilityMode.STANDARD,
				completed && mode == ListenerVisibilityMode.STANDARD,
				true, completed && mode == ListenerVisibilityMode.STANDARD, List.of());
	}

	private StudioProfileResponseDto studioResponse(
			UUID profileId,
			UUID userId,
			String name,
			String description,
			UUID pictureId,
			Set<String> facilities,
			String website,
			String instagram,
			long version
	) {
		return new StudioProfileResponseDto(
				profileId, userId, name, description, pictureId, null,
				"Nota Sok. No: 8", location.city().getId(), "İstanbul",
				location.district().getId(), "Kadıköy", location.neighborhood().getId(), "Caferağa",
				"05550000001", website, facilities, instagram, null,
				"Europe/Istanbul", version, List.of(), List.of(), 0, 0);
	}
}
