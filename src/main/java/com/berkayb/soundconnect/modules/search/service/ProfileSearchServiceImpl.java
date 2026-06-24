package com.berkayb.soundconnect.modules.search.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.search.dto.ProfileSearchItemDto;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileSearchServiceImpl implements ProfileSearchService {
	private static final int MIN_QUERY_LENGTH = 2;
	private static final int DEFAULT_LIMIT = 15;
	private static final int MAX_LIMIT = 30;

	private final MusicianProfileRepository musicianProfileRepository;
	private final ListenerProfileRepository listenerProfileRepository;
	private final BandRepository bandRepository;
	private final StudioProfileRepository studioProfileRepository;
	private final VenueRepository venueRepository;
	private final MediaAssetService mediaAssetService;

	@Override
	@Transactional(readOnly = true)
	public List<ProfileSearchItemDto> searchProfiles(String query, int limit) {
		String q = query == null ? "" : query.trim();
		if (q.length() < MIN_QUERY_LENGTH) return List.of();

		int safeLimit = clampLimit(limit);
		int perTypeLimit = Math.max(5, safeLimit);
		List<ProfileSearchItemDto> results = new ArrayList<>();

		musicianProfileRepository.searchByStageNameOrUsername(q)
		                         .stream()
		                         .limit(perTypeLimit)
		                         .map(profile -> new ProfileSearchItemDto(
				                         "MUSICIAN",
				                         profile.getId(),
				                         profile.getUser().getId(),
				                         firstNotBlank(profile.getStageName(), profile.getUser().getUsername(), "Müzisyen"),
				                         profile.getUser().getUsername(),
				                         resolveMediaUrl(profile.getProfilePictureMediaId())
		                         ))
		                         .forEach(results::add);

		listenerProfileRepository.searchByUsernameOrBio(q)
		                         .stream()
		                         .limit(perTypeLimit)
		                         .map(profile -> new ProfileSearchItemDto(
				                         "LISTENER",
				                         profile.getId(),
				                         profile.getUser().getId(),
				                         firstNotBlank(profile.getName(), profile.getUser().getUsername(), "Dinleyici"),
				                         profile.getUser().getUsername(),
				                         resolveMediaUrl(profile.getProfilePictureMediaId())
		                         ))
		                         .forEach(results::add);

		bandRepository.searchByName(q)
		              .stream()
		              .limit(perTypeLimit)
		              .map(band -> new ProfileSearchItemDto(
				              "BAND",
				              band.getId(),
				              null,
				              firstNotBlank(band.getName(), "Band"),
				              "Band",
				              resolveMediaUrl(band.getProfilePictureMediaId())
		              ))
		              .forEach(results::add);

		studioProfileRepository.searchByNameUsernameOrDescription(q)
		                       .stream()
		                       .limit(perTypeLimit)
		                       .map(profile -> new ProfileSearchItemDto(
				                       "STUDIO",
				                       profile.getId(),
				                       profile.getUser().getId(),
				                       firstNotBlank(profile.getName(), profile.getUser().getUsername(), "Stüdyo"),
				                       firstNotBlank(profile.getDescription(), "Stüdyo"),
				                       resolveMediaUrl(profile.getProfilePictureMediaId())
		                       ))
		                       .forEach(results::add);

		venueRepository.findByNameContainingIgnoreCase(q, PageRequest.of(0, perTypeLimit))
		               .stream()
		               .map(venue -> new ProfileSearchItemDto(
				               "VENUE",
				               venue.getId(),
				               venue.getOwner() != null ? venue.getOwner().getId() : null,
				               firstNotBlank(venue.getName(), "Mekan"),
				               firstNotBlank(venue.getDistrict() != null ? venue.getDistrict().getName() : null,
						               venue.getCity() != null ? venue.getCity().getName() : null,
						               "Mekan"),
				               null
		               ))
		               .forEach(results::add);

		return results.stream()
		              .sorted(Comparator.comparingInt(item -> rank(item.title(), q)))
		              .limit(safeLimit)
		              .toList();
	}

	private int clampLimit(int limit) {
		if (limit <= 0) return DEFAULT_LIMIT;
		return Math.min(limit, MAX_LIMIT);
	}

	private int rank(String value, String query) {
		String normalized = value == null ? "" : value.trim().toLowerCase();
		String q = query.trim().toLowerCase();
		if (normalized.equals(q)) return 0;
		if (normalized.startsWith(q)) return 1;
		return 2;
	}

	private String firstNotBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) return value.trim();
		}
		return "";
	}

	private String resolveMediaUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getById(mediaAssetId).getSourceUrl();
		} catch (Exception e) {
			log.warn("Profile search image resolve failed. mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
}
