package com.berkayb.soundconnect.modules.feed.musician.preference.service;

import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedCompletionTaskCode;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesUpdate;
import com.berkayb.soundconnect.modules.feed.musician.preference.entity.MusicianFeedPreferences;
import com.berkayb.soundconnect.modules.feed.musician.preference.repository.MusicianFeedPreferencesRepository;
import com.berkayb.soundconnect.modules.feed.musician.preference.repository.MusicianFeedProfileReadRepository;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MusicianFeedPreferencesServiceImplTest {
	@Mock MusicianFeedPreferencesRepository preferencesRepository;
	@Mock MusicianFeedProfileReadRepository profileRepository;
	@Mock CityRepository cityRepository;
	@InjectMocks MusicianFeedPreferencesServiceImpl service;

	private final UUID userId = UUID.randomUUID();
	private final UUID profileId = UUID.randomUUID();
	private MusicianProfile profile;

	@BeforeEach
	void profile() {
		profile = MusicianProfile.builder().id(profileId).instruments(new HashSet<>()).build();
	}

	@Test
	void missingPreferenceIsAReadableVersionZeroStateAndNeverCreatesARow() {
		stubRead(false);

		var response = service.get(userId);

		assertThat(response.contractVersion()).isEqualTo(1);
		assertThat(response.version()).isZero();
		assertThat(response.opportunityCity()).isNull();
		assertThat(response.instruments()).isEmpty();
		assertThat(response.completion().personalizationReadiness().complete()).isFalse();
		assertThat(response.completion().incompleteTasks().getFirst().code())
				.isEqualTo(MusicianFeedCompletionTaskCode.OPPORTUNITY_CITY);
		verify(preferencesRepository, never()).saveAndFlush(any());
	}

	@Test
	void instrumentsAreReturnedWithCanonicalIdsInDeterministicOrder() {
		Instrument zurna = instrument("Zurna");
		Instrument gitar = instrument("gitar");
		profile.setInstruments(Set.of(zurna, gitar));
		stubRead(false);

		var response = service.get(userId);

		assertThat(response.instruments()).extracting(item -> item.name()).containsExactly("gitar", "Zurna");
		assertThat(response.instruments()).extracting(item -> item.id()).containsExactly(gitar.getId(), zurna.getId());
	}

	@Test
	void firstCitySelectionCreatesOnlyTheSeparatePreferenceAndAdvancesItsRevision() {
		UUID cityId = UUID.randomUUID();
		City city = city(cityId, "İstanbul");
		stubWrite(null, false);
		when(cityRepository.findById(cityId)).thenReturn(Optional.of(city));

		var response = service.update(userId, new MusicianFeedPreferencesUpdate(cityId, 0L));

		assertThat(response.version()).isEqualTo(1);
		assertThat(response.opportunityCity().id()).isEqualTo(cityId);
		assertThat(response.opportunityCity().name()).isEqualTo("İstanbul");
		verify(preferencesRepository).saveAndFlush(argThat(saved -> saved.getMusicianProfileId().equals(profileId)
				&& saved.getOpportunityCity() == city && saved.getVersion() == 1));
	}

	@Test
	void clearingCityIsAnExplicitVersionedReplacement() {
		City old = city(UUID.randomUUID(), "Ankara");
		MusicianFeedPreferences preferences = preferences(old, 4);
		stubWrite(preferences, false);

		var response = service.update(userId, new MusicianFeedPreferencesUpdate(null, 4L));

		assertThat(response.version()).isEqualTo(5);
		assertThat(response.opportunityCity()).isNull();
		verify(preferencesRepository).saveAndFlush(preferences);
		verifyNoInteractions(cityRepository);
	}

	@Test
	void currentValueIsANoOpAndDoesNotConsumeARevision() {
		City city = city(UUID.randomUUID(), "İzmir");
		MusicianFeedPreferences preferences = preferences(city, 3);
		stubWrite(preferences, false);

		var response = service.update(userId, new MusicianFeedPreferencesUpdate(city.getId(), 3L));

		assertThat(response.version()).isEqualTo(3);
		verify(preferencesRepository, never()).saveAndFlush(any());
		verifyNoInteractions(cityRepository);
	}

	@Test
	void exactResponseLostRetryIsIdempotent() {
		City city = city(UUID.randomUUID(), "Bursa");
		MusicianFeedPreferences preferences = preferences(city, 8);
		stubWrite(preferences, false);

		var response = service.update(userId, new MusicianFeedPreferencesUpdate(city.getId(), 7L));

		assertThat(response.version()).isEqualTo(8);
		verify(preferencesRepository, never()).saveAndFlush(any());
		verifyNoInteractions(cityRepository);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, 6, 9, 100})
	void staleOrFutureRevisionCannotOverwriteAnotherDevice(long suppliedVersion) {
		MusicianFeedPreferences preferences = preferences(city(UUID.randomUUID(), "Adana"), 8);
		when(profileRepository.lockOwnedProfileForUpdate(userId)).thenReturn(Optional.of(profileId));
		when(preferencesRepository.findWithOpportunityCity(profileId)).thenReturn(Optional.of(preferences));

		assertThatThrownBy(() -> service.update(userId,
				new MusicianFeedPreferencesUpdate(UUID.randomUUID(), suppliedVersion)))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.MUSICIAN_FEED_PREFERENCE_VERSION_CONFLICT));
		verify(preferencesRepository, never()).saveAndFlush(any());
		verifyNoInteractions(cityRepository);
	}

	@Test
	void unknownCityFailsWithoutMutatingTheCurrentPreference() {
		UUID unknown = UUID.randomUUID();
		when(profileRepository.lockOwnedProfileForUpdate(userId)).thenReturn(Optional.of(profileId));
		when(preferencesRepository.findWithOpportunityCity(profileId)).thenReturn(Optional.empty());
		when(cityRepository.findById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.update(userId, new MusicianFeedPreferencesUpdate(unknown, 0L)))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CITY_NOT_FOUND));
		verify(preferencesRepository, never()).saveAndFlush(any());
		verify(profileRepository, never()).findByIdWithInstruments(any());
	}

	@Test
	void authenticatedRoleWithoutAnOwnedMusicianProfileCannotInventPreferences() {
		assertThatThrownBy(() -> service.get(userId))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.PROFILE_NOT_FOUND));
		verify(profileRepository).lockOwnedProfileForRead(userId);
		verifyNoInteractions(preferencesRepository, cityRepository);
	}

	@ParameterizedTest
	@ValueSource(longs = {-1, Long.MAX_VALUE})
	void invalidDirectServiceVersionsNeverAcquireALock(long version) {
		assertThatThrownBy(() -> service.update(userId, new MusicianFeedPreferencesUpdate(null, version)))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
		verifyNoInteractions(profileRepository, preferencesRepository, cityRepository);
	}

	private void stubRead(boolean portfolio) {
		when(profileRepository.lockOwnedProfileForRead(userId)).thenReturn(Optional.of(profileId));
		when(preferencesRepository.findWithOpportunityCity(profileId)).thenReturn(Optional.empty());
		stubResponse(portfolio);
	}

	private void stubWrite(MusicianFeedPreferences preferences, boolean portfolio) {
		when(profileRepository.lockOwnedProfileForUpdate(userId)).thenReturn(Optional.of(profileId));
		when(preferencesRepository.findWithOpportunityCity(profileId))
				.thenReturn(Optional.ofNullable(preferences));
		stubResponse(portfolio);
	}

	private void stubResponse(boolean portfolio) {
		when(profileRepository.findByIdWithInstruments(profileId)).thenReturn(Optional.of(profile));
		when(profileRepository.hasPublicPortfolio(profileId)).thenReturn(portfolio);
	}

	private MusicianFeedPreferences preferences(City city, long version) {
		MusicianFeedPreferences preferences = new MusicianFeedPreferences(profileId);
		preferences.setOpportunityCity(city);
		preferences.setVersion(version);
		return preferences;
	}

	private static Instrument instrument(String name) {
		Instrument instrument = Instrument.builder().name(name).build();
		instrument.setId(UUID.randomUUID());
		return instrument;
	}

	private static City city(UUID id, String name) {
		return City.builder().id(id).name(name).build();
	}
}
