package com.berkayb.soundconnect.modules.search.service;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.search.dto.ProfileSearchItemDto;
import com.berkayb.soundconnect.modules.search.enums.ProfileSearchType;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileSearchServiceImpl implements ProfileSearchService {
	private static final int MIN_QUERY_LENGTH = 2;
	private static final int MAX_QUERY_LENGTH = 100;
	private static final int DEFAULT_LIMIT = 15;
	private static final int MAX_LIMIT = 30;
	private static final Set<String> GHOST_CONFLICTING_PERSONAL_RESULT_TYPES =
			Set.of("MUSICIAN", "STUDIO", "VENUE");

	private final MusicianProfileRepository musicianProfileRepository;
	private final ListenerProfileRepository listenerProfileRepository;
	private final BandRepository bandRepository;
	private final StudioProfileRepository studioProfileRepository;
	private final VenueRepository venueRepository;
	private final MediaAssetService mediaAssetService;
	private final ListenerVisibilityPolicy listenerVisibilityPolicy;

	@Override
	@Transactional
	public List<ProfileSearchItemDto> searchProfiles(String query, int limit) {
		return searchProfiles(query, limit, null);
	}

	@Override
	@Transactional
	public List<ProfileSearchItemDto> searchProfiles(
			String query,
			int limit,
			Set<ProfileSearchType> types
	) {
		String q = query == null ? "" : UsernameUtils.stripBoundaryWhitespace(query);
		if (q.length() < MIN_QUERY_LENGTH) return List.of();
		if (q.length() > MAX_QUERY_LENGTH) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					"Profile search query cannot exceed " + MAX_QUERY_LENGTH + " characters"
			);
		}
		String usernameQuery = UsernameUtils.normalize(q);

		int safeLimit = clampLimit(limit);
		int perTypeLimit = Math.max(5, safeLimit);
		PageRequest perTypePage = PageRequest.of(0, perTypeLimit);
		Set<ProfileSearchType> requestedTypes = normalizeTypes(types);
		List<SearchCandidate> candidates = new ArrayList<>();

		if (requestedTypes.contains(ProfileSearchType.MUSICIAN)) {
			musicianProfileRepository.searchByStageNameOrUsername(q, usernameQuery, perTypePage)
		                         .stream()
		                         .limit(perTypeLimit)
		                         .map(profile -> new SearchCandidate(
				                         "MUSICIAN",
				                         profile.getId(),
				                         profile.getUser().getId(),
				                         firstNotBlank(profile.getStageName(), profile.getUser().getUsername(), "Müzisyen"),
					                         profile.getUser().getUsername(),
					                         profile.getProfilePictureMediaId(),
					                         null,
					                         null
		                         ))
		                         .forEach(candidates::add);
		}

		if (requestedTypes.contains(ProfileSearchType.LISTENER)) {
			listenerProfileRepository.searchForPublicDiscovery(
						 q,
						 usernameQuery,
						 ListenerVisibilityMode.GHOST,
						 perTypePage
					 )
		                         .stream()
		                         .limit(perTypeLimit)
		                         .map(profile -> new SearchCandidate(
				                         "LISTENER",
				                         profile.getId(),
				                         profile.getUser().getId(),
				                         profile.isGhost()
							                         ? firstNotBlank(profile.getUser().getUsername(), "Dinleyici")
							                         : firstNotBlank(profile.getName(), profile.getUser().getUsername(), "Dinleyici"),
					                         profile.isGhost() ? null : profile.getUser().getUsername(),
					                         profile.getProfilePictureMediaId(),
					                         profile.getUser().getUsername(),
					                         profile.isGhost() ? ListenerVisibilityMode.GHOST : null
		                         ))
		                         .forEach(candidates::add);
		}

		if (requestedTypes.contains(ProfileSearchType.BAND)) {
			bandRepository.searchByName(q, perTypePage)
		              .stream()
		              .limit(perTypeLimit)
		              .map(band -> new SearchCandidate(
				              "BAND",
				              band.getId(),
				              null,
				              firstNotBlank(band.getName(), "Band"),
					              "Band",
					              band.getProfilePictureMediaId(),
					              null,
					              null
		              ))
		              .forEach(candidates::add);
		}

		if (requestedTypes.contains(ProfileSearchType.STUDIO)) {
			studioProfileRepository.searchByNameUsernameOrDescription(q, usernameQuery, perTypePage)
		                       .stream()
		                       .limit(perTypeLimit)
		                       .map(profile -> new SearchCandidate(
				                       "STUDIO",
				                       profile.getId(),
				                       profile.getUser().getId(),
				                       firstNotBlank(profile.getName(), profile.getUser().getUsername(), "Stüdyo"),
					                       studioLocation(profile),
					                       profile.getProfilePictureMediaId(),
					                       null,
					                       null
		                       ))
		                       .forEach(candidates::add);
		}

		if (requestedTypes.contains(ProfileSearchType.VENUE)) {
			venueRepository.searchByNameOrOwnerUsername(q, usernameQuery, perTypePage)
		               .stream()
		               .map(venue -> new SearchCandidate(
				               "VENUE",
				               venue.getId(),
				               venue.getOwner() != null ? venue.getOwner().getId() : null,
				               firstNotBlank(venue.getName(), "Mekan"),
				               firstNotBlank(venue.getDistrict() != null ? venue.getDistrict().getName() : null,
						               venue.getCity() != null ? venue.getCity().getName() : null,
					               "Mekan"),
					               null,
					               null,
					               null
		               ))
		               .forEach(candidates::add);
		}

		ListenerVisibilityPolicy.PublicVisibilityRestrictions restrictions = Objects.requireNonNull(
				listenerVisibilityPolicy.publicVisibilityRestrictions(
				candidates.stream()
				          .map(SearchCandidate::ownerUserId)
				          .filter(userId -> userId != null)
				          .toList()
		), "Listener visibility policy returned null");
		Set<UUID> ghostUserIds = restrictions.ghostUserIds();
		Set<UUID> pendingChoiceUserIds = restrictions.pendingChoiceUserIds();

		List<SearchCandidate> selected = candidates.stream()
		              .filter(candidate -> !isOwnedByPendingListener(candidate, pendingChoiceUserIds))
		              .map(candidate -> reprojectGhostListener(candidate, ghostUserIds))
		              .filter(candidate -> !isConflictingGhostPersonalResult(candidate, ghostUserIds))
		              .sorted(Comparator.comparingInt(candidate -> rank(candidate.title(), q)))
		              .limit(safeLimit)
		              .toList();
		Map<UUID, String> mediaUrls = resolveMediaUrls(selected);

		return selected.stream()
		              .map(candidate -> new ProfileSearchItemDto(
			              candidate.type(),
			              candidate.profileId(),
			              candidate.ownerUserId(),
			              candidate.title(),
			              candidate.subtitle(),
			              candidate.mediaAssetId() == null ? null : mediaUrls.get(candidate.mediaAssetId()),
			              candidate.visibilityMode()
		              ))
		              .toList();
	}

	private int clampLimit(int limit) {
		if (limit <= 0) return DEFAULT_LIMIT;
		return Math.min(limit, MAX_LIMIT);
	}

	private Set<ProfileSearchType> normalizeTypes(Set<ProfileSearchType> types) {
		return types == null || types.isEmpty()
				? EnumSet.allOf(ProfileSearchType.class)
				: EnumSet.copyOf(types);
	}

	private int rank(String value, String query) {
		String normalized = foldForSearch(value);
		String q = foldForSearch(query);
		if (normalized.equals(q)) return 0;
		if (normalized.startsWith(q)) return 1;
		return 2;
	}

	private String foldForSearch(String value) {
		if (value == null) return "";
		String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD);
		StringBuilder folded = new StringBuilder(decomposed.length());
		for (int offset = 0; offset < decomposed.length(); ) {
			int codePoint = decomposed.codePointAt(offset);
			offset += Character.charCount(codePoint);
			if (Character.getType(codePoint) == Character.NON_SPACING_MARK) continue;
			if (codePoint == '\u0131') codePoint = 'i';
			folded.appendCodePoint(Character.toLowerCase(codePoint));
		}
		return folded.toString().toLowerCase(Locale.ROOT);
	}

	private String firstNotBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) return value.trim();
		}
		return "";
	}

	private String studioLocation(StudioProfile profile) {
		List<String> parts = new ArrayList<>(3);
		if (profile.getNeighborhood() != null) {
			addIfNotBlank(parts, profile.getNeighborhood().getName());
		}
		if (profile.getDistrict() != null) {
			addIfNotBlank(parts, profile.getDistrict().getName());
		}
		if (profile.getCity() != null) {
			addIfNotBlank(parts, profile.getCity().getName());
		}
		return parts.isEmpty() ? "Stüdyo" : String.join(", ", parts);
	}

	private boolean isOwnedByPendingListener(
			SearchCandidate candidate,
			Set<UUID> pendingChoiceUserIds
	) {
		return candidate.ownerUserId() != null
				&& pendingChoiceUserIds.contains(candidate.ownerUserId());
	}

	private boolean isConflictingGhostPersonalResult(SearchCandidate candidate, Set<UUID> ghostUserIds) {
		return candidate.ownerUserId() != null
				&& ghostUserIds.contains(candidate.ownerUserId())
				&& GHOST_CONFLICTING_PERSONAL_RESULT_TYPES.contains(candidate.type());
	}

	private SearchCandidate reprojectGhostListener(SearchCandidate candidate, Set<UUID> ghostUserIds) {
		if (!"LISTENER".equals(candidate.type())) return candidate;
		boolean ghost = candidate.visibilityMode() == ListenerVisibilityMode.GHOST
				|| (candidate.ownerUserId() != null && ghostUserIds.contains(candidate.ownerUserId()));
		if (!ghost) return candidate;
		return new SearchCandidate(
				candidate.type(),
				candidate.profileId(),
				candidate.ownerUserId(),
				firstNotBlank(candidate.canonicalUsername(), "Dinleyici"),
				null,
				candidate.mediaAssetId(),
				candidate.canonicalUsername(),
				ListenerVisibilityMode.GHOST
		);
	}

	private void addIfNotBlank(List<String> values, String value) {
		if (value != null && !value.isBlank()) values.add(value.trim());
	}

	private Map<UUID, String> resolveMediaUrls(List<SearchCandidate> candidates) {
		LinkedHashSet<UUID> mediaAssetIds = new LinkedHashSet<>();
		for (SearchCandidate candidate : candidates) {
			if (candidate.mediaAssetId() != null) mediaAssetIds.add(candidate.mediaAssetId());
		}
		if (mediaAssetIds.isEmpty()) return Map.of();
		try {
			Map<UUID, String> urls = mediaAssetService.getDisplayUrlMap(List.copyOf(mediaAssetIds));
			return urls == null ? Map.of() : urls;
		} catch (RuntimeException exception) {
			log.warn("Profile search image batch resolve failed. assetCount={}, exceptionType={}",
					mediaAssetIds.size(), exception.getClass().getSimpleName());
			return Map.of();
		}
	}

	private record SearchCandidate(
			String type,
			UUID profileId,
			UUID ownerUserId,
			String title,
			String subtitle,
			UUID mediaAssetId,
			String canonicalUsername,
			ListenerVisibilityMode visibilityMode
	) {}
}
