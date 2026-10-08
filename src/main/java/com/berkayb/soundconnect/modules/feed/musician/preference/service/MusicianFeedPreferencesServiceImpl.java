package com.berkayb.soundconnect.modules.feed.musician.preference.service;

import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesResponse;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianFeedPreferencesUpdate;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.MusicianInstrumentSummary;
import com.berkayb.soundconnect.modules.feed.musician.preference.dto.OpportunityCitySummary;
import com.berkayb.soundconnect.modules.feed.musician.preference.entity.MusicianFeedPreferences;
import com.berkayb.soundconnect.modules.feed.musician.preference.repository.MusicianFeedPreferencesRepository;
import com.berkayb.soundconnect.modules.feed.musician.preference.repository.MusicianFeedProfileReadRepository;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MusicianFeedPreferencesServiceImpl implements MusicianFeedPreferencesService {
	static final int CONTRACT_VERSION = 1;

	private static final Comparator<Instrument> INSTRUMENT_ORDER =
			Comparator.comparing(Instrument::getName, String.CASE_INSENSITIVE_ORDER)
					.thenComparing(Instrument::getName)
					.thenComparing(instrument -> instrument.getId().toString());

	private final MusicianFeedPreferencesRepository preferencesRepository;
	private final MusicianFeedProfileReadRepository profileRepository;
	private final CityRepository cityRepository;

	// PostgreSQL does not allow FOR SHARE in a read-only transaction. The shared
	// parent lock gives this multi-query response one profile/instrument snapshot.
	@Override
	@Transactional
	public MusicianFeedPreferencesResponse get(UUID userId) {
		UUID profileId = profileRepository.lockOwnedProfileForRead(userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		MusicianFeedPreferences preferences = preferencesRepository.findWithOpportunityCity(profileId)
				.orElseGet(() -> new MusicianFeedPreferences(profileId));
		return response(profileId, preferences);
	}

	@Override
	@Transactional
	public MusicianFeedPreferencesResponse update(UUID userId, MusicianFeedPreferencesUpdate update) {
		validate(update);
		UUID profileId = profileRepository.lockOwnedProfileForUpdate(userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		MusicianFeedPreferences current = preferencesRepository.findWithOpportunityCity(profileId)
				.orElseGet(() -> new MusicianFeedPreferences(profileId));
		UUID currentCityId = cityId(current);
		UUID requestedCityId = update.opportunityCityId();

		if (update.expectedVersion() != current.getVersion()) {
			// A response-lost retry is an idempotent success, but no other stale or
			// future revision may overwrite a preference from another device.
			if (current.getVersion() == update.expectedVersion() + 1
					&& Objects.equals(currentCityId, requestedCityId)) {
				return response(profileId, current);
			}
			throw new SoundConnectException(ErrorType.MUSICIAN_FEED_PREFERENCE_VERSION_CONFLICT);
		}

		if (Objects.equals(currentCityId, requestedCityId)) return response(profileId, current);

		City requestedCity = requestedCityId == null ? null : cityRepository.findById(requestedCityId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.CITY_NOT_FOUND));
		current.setOpportunityCity(requestedCity);
		current.setVersion(current.getVersion() + 1);
		preferencesRepository.saveAndFlush(current);
		return response(profileId, current);
	}

	private MusicianFeedPreferencesResponse response(UUID profileId, MusicianFeedPreferences preferences) {
		MusicianProfile profile = profileRepository.findByIdWithInstruments(profileId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		List<MusicianInstrumentSummary> instruments = profile.getInstruments().stream()
				.sorted(INSTRUMENT_ORDER)
				.map(instrument -> new MusicianInstrumentSummary(instrument.getId(), instrument.getName()))
				.toList();
		boolean hasPortfolio = profileRepository.hasPublicPortfolio(profileId);
		City city = preferences.getOpportunityCity();
		OpportunityCitySummary citySummary = city == null ? null : new OpportunityCitySummary(city.getId(), city.getName());

		return new MusicianFeedPreferencesResponse(
				CONTRACT_VERSION,
				preferences.getVersion(),
				citySummary,
				instruments,
				MusicianFeedCompletionCalculator.calculate(profile, city != null, hasPortfolio)
		);
	}

	private static UUID cityId(MusicianFeedPreferences preferences) {
		return preferences.getOpportunityCity() == null ? null : preferences.getOpportunityCity().getId();
	}

	private static void validate(MusicianFeedPreferencesUpdate update) {
		if (update == null || update.expectedVersion() == null || update.expectedVersion() < 0
				|| update.expectedVersion() == Long.MAX_VALUE) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
		}
	}
}
