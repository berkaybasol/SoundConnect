package com.berkayb.soundconnect.modules.profile.StudioProfile.service;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileProvisioningCommand;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.response.StudioProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.mapper.StudioProfileMapper;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.spotify.dto.response.SpotifyTrackItemDto;
import com.berkayb.soundconnect.modules.spotify.service.SpotifyService;
import com.berkayb.soundconnect.modules.studio.room.repository.StudioRoomRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudioProfileServiceImpl implements StudioProfileService {
	private static final String DEFAULT_TIME_ZONE = "Europe/Istanbul";
	private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
	private static final Pattern PHONE_CHARACTERS = Pattern.compile("^(?:\\+)?[0-9() .-]+$");
	private static final Pattern NON_PHONE_DIGITS = Pattern.compile("\\D");
	private static final Pattern URI_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");

	private final StudioProfileRepository studioProfileRepository;
	private final UserEntityFinder userEntityFinder;
	private final StudioProfileMapper studioProfileMapper;
	private final MediaAssetService mediaAssetService;
	private final StudioRoomRepository studioRoomRepository;
	private final SpotifyService spotifyService;
	private final StudioProfileTransactionExecutor transactionExecutor;
	private final LocationEntityFinder locationEntityFinder;

	@Override
	public StudioProfileResponseDto createProfile(UUID userId, StudioProfileSaveRequestDto dto) {
		String name = normalizeRequiredName(dto.name());
		List<String> spotifyTrackIds = normalizeSpotifyIds(dto.spotifyTrackIds());
		List<SpotifyTrackItemDto> spotifyTracks = hydrateSpotifyTracks(spotifyTrackIds);
		return transactionExecutor.execute(() -> createProfileTransactional(
				userId, dto, name, spotifyTrackIds, spotifyTracks
		));
	}

	@Override
	@Transactional
	public StudioProfileResponseDto createApprovedProfile(StudioProfileProvisioningCommand command) {
		if (command == null || command.userId() == null) {
			throw new SoundConnectException(ErrorType.BAD_REQUEST);
		}
		User user = userEntityFinder.getUser(command.userId());
		if (studioProfileRepository.findByUserId(command.userId()).isPresent()) {
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		City city = locationEntityFinder.getCity(command.cityId());
		District district = locationEntityFinder.getDistrict(command.districtId());
		Neighborhood neighborhood = locationEntityFinder.getNeighborhood(command.neighborhoodId());
		validateLocationHierarchy(city, district, neighborhood);

		StudioProfile profile = StudioProfile.builder()
				.user(user)
				.name(normalizeBusinessName(command.name()))
				.address(normalizeNullable(command.address()))
				.phone(normalizePhone(command.phone()))
				.city(city)
				.district(district)
				.neighborhood(neighborhood)
				.timeZone(DEFAULT_TIME_ZONE)
				.build();
		StudioProfile saved = studioProfileRepository.saveAndFlush(profile);
		log.info("Approved studio profile provisioned userId={} profileId={}", command.userId(), saved.getId());
		return toResponseDto(saved);
	}

	private StudioProfileResponseDto createProfileTransactional(
			UUID userId,
			StudioProfileSaveRequestDto dto,
			String name,
			List<String> spotifyTrackIds,
			List<SpotifyTrackItemDto> spotifyTracks
	) {
		User user = userEntityFinder.getUser(userId);
		if (studioProfileRepository.findByUserId(userId).isPresent()) {
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		StudioProfile profile = StudioProfile.builder()
				.user(user)
				.name(normalizeBusinessName(name))
				.description(capitalizeFirst(normalizeNullable(dto.descpriction())))
				.address(normalizeNullable(dto.adress()))
				.phone(normalizePhone(dto.phone()))
				.website(normalizeWebUrl(dto.website(), "website"))
				.facilities(normalizeLabels(dto.facilities()))
				.instagramUrl(normalizeWebUrl(dto.instagramUrl(), "instagramUrl"))
				.youtubeUrl(normalizeWebUrl(dto.youtubeUrl(), "youtubeUrl"))
				.timeZone(validateTimeZone(dto.timeZone()))
				.spotifyTrackIds(spotifyTrackIds)
				.spotifyTracks(spotifyTracks)
				.build();

		StudioProfile saved = studioProfileRepository.saveAndFlush(profile);
		if (dto.profilePicture() != null) {
			validateProfilePicture(userId, saved.getId(), dto.profilePicture());
			saved.setProfilePictureMediaId(dto.profilePicture());
			// Force the profile-picture mutation (and its optimistic-lock bump)
			// before constructing the response. Returning the pre-flush version
			// would make the client's very next owner update stale.
			saved = studioProfileRepository.saveAndFlush(saved);
		}
		log.info("Studio profile created userId={} profileId={}", userId, saved.getId());
		return toResponseDto(saved);
	}

	@Override
	@Transactional(readOnly = true)
	public StudioProfileResponseDto getProfileByUserId(UUID userId) {
		userEntityFinder.getUser(userId);
		return toResponseDto(studioProfileRepository.findByUserId(userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND)));
	}

	@Override
	@Transactional(readOnly = true)
	public StudioProfileResponseDto getProfileByProfileId(UUID profileId) {
		return toResponseDto(studioProfileRepository.findById(profileId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND)));
	}

	@Override
	public StudioProfileResponseDto updateProfile(UUID userId, StudioProfileSaveRequestDto dto) {
		List<String> spotifyTrackIds = dto.spotifyTrackIds() == null
				? null
				: normalizeSpotifyIds(dto.spotifyTrackIds());
		List<SpotifyTrackItemDto> spotifyTracks = spotifyTrackIds == null
				? null
				: hydrateSpotifyTracks(spotifyTrackIds);
		return transactionExecutor.execute(() -> updateProfileTransactional(
				userId, dto, spotifyTrackIds, spotifyTracks
		));
	}

	private StudioProfileResponseDto updateProfileTransactional(
			UUID userId,
			StudioProfileSaveRequestDto dto,
			List<String> spotifyTrackIds,
			List<SpotifyTrackItemDto> spotifyTracks
	) {
		userEntityFinder.getUser(userId);
		StudioProfile profile = studioProfileRepository.findByUserIdForUpdate(userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));

		if (dto.version() != null && dto.version() != profile.getVersion()) {
			throw new SoundConnectException(ErrorType.STUDIO_STALE_UPDATE);
		}
		if (dto.name() != null) profile.setName(normalizeBusinessName(dto.name()));
		if (dto.descpriction() != null) profile.setDescription(capitalizeFirst(normalizeNullable(dto.descpriction())));
		if (dto.adress() != null) profile.setAddress(normalizeNullable(dto.adress()));
		if (dto.phone() != null) profile.setPhone(normalizePhone(dto.phone()));
		if (dto.website() != null) profile.setWebsite(normalizeWebUrl(dto.website(), "website"));
		if (dto.facilities() != null) profile.setFacilities(normalizeLabels(dto.facilities()));
		if (dto.instagramUrl() != null) {
			profile.setInstagramUrl(normalizeWebUrl(dto.instagramUrl(), "instagramUrl"));
		}
		if (dto.youtubeUrl() != null) {
			profile.setYoutubeUrl(normalizeWebUrl(dto.youtubeUrl(), "youtubeUrl"));
		}
		if (dto.timeZone() != null) {
			String requestedTimeZone = validateTimeZone(dto.timeZone());
			if (!requestedTimeZone.equals(profile.getTimeZone())
					&& studioRoomRepository.existsByStudioProfileId(profile.getId())) {
				throw new SoundConnectException(ErrorType.STUDIO_TIME_ZONE_LOCKED);
			}
			profile.setTimeZone(requestedTimeZone);
		}
		if (dto.spotifyTrackIds() != null) {
			profile.setSpotifyTrackIds(spotifyTrackIds);
			profile.setSpotifyTracks(spotifyTracks);
		}
		if (dto.profilePicture() != null) {
			validateProfilePicture(userId, profile.getId(), dto.profilePicture());
			profile.setProfilePictureMediaId(dto.profilePicture());
		}

		// @Version is incremented on flush, not on save(). The response must
		// contain the authoritative token that the next client write will use.
		StudioProfile updated = studioProfileRepository.saveAndFlush(profile);
		log.info("Studio profile updated userId={} profileId={} version={}",
				userId, profile.getId(), profile.getVersion());
		return toResponseDto(updated);
	}

	private void validateProfilePicture(UUID userId, UUID profileId, UUID mediaAssetId) {
		mediaAssetService.validateAssignableMedia(
				userId,
				mediaAssetId,
				MediaOwnerType.STUDIO_PROFILE,
				profileId,
				MediaKind.IMAGE
		);
	}

	private StudioProfileResponseDto toResponseDto(StudioProfile profile) {
		StudioProfileResponseDto dto = studioProfileMapper.toDto(profile);
		return new StudioProfileResponseDto(
				dto.id(), dto.userId(), dto.name(), dto.description(),
				dto.profilePictureMediaId(), resolveProfilePictureUrl(dto.profilePictureMediaId()),
				dto.adress(), dto.cityId(), dto.cityName(), dto.districtId(), dto.districtName(),
				dto.neighborhoodId(), dto.neighborhoodName(),
				safeResponsePhone(dto.phone(), profile.getId()),
				safeResponseUrl(dto.website(), "website", profile.getId()),
				dto.facilities() == null ? Set.of() : Set.copyOf(dto.facilities()),
				safeResponseUrl(dto.instagramUrl(), "instagramUrl", profile.getId()),
				safeResponseUrl(dto.youtubeUrl(), "youtubeUrl", profile.getId()),
				safeResponseTimeZone(profile.getTimeZone(), profile.getId()), profile.getVersion(),
				profile.getSpotifyTrackIds() == null ? List.of() : List.copyOf(profile.getSpotifyTrackIds()),
				profile.getSpotifyTracks() == null ? List.of() : List.copyOf(profile.getSpotifyTracks()),
				studioProfileRepository.countActiveRooms(profile.getId()),
				studioProfileRepository.sumActiveBacklineUnits(profile.getId())
		);
	}

	private void validateLocationHierarchy(City city, District district, Neighborhood neighborhood) {
		if (!district.getCity().getId().equals(city.getId())) {
			throw new SoundConnectException(ErrorType.DISTRICT_CITY_MISMATCH);
		}
		if (!neighborhood.getDistrict().getId().equals(district.getId())) {
			throw new SoundConnectException(ErrorType.NEIGHBORHOOD_DISTRICT_MISMATCH);
		}
	}

	private String resolveProfilePictureUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getPlaybackUrl(mediaAssetId);
		} catch (RuntimeException exception) {
			log.warn("Studio profile picture unavailable mediaAssetId={}", mediaAssetId);
			return null;
		}
	}

	private String validateTimeZone(String value) {
		String candidate = normalizeNullable(value);
		if (candidate == null) return DEFAULT_TIME_ZONE;
		try {
			return ZoneId.of(candidate).getId();
		} catch (DateTimeException exception) {
			throw new SoundConnectException(ErrorType.BAD_REQUEST);
		}
	}

	private Set<String> normalizeLabels(Set<String> values) {
		if (values == null || values.isEmpty()) return new LinkedHashSet<>();
		LinkedHashSet<String> result = new LinkedHashSet<>();
		Set<String> normalized = new LinkedHashSet<>();
		for (String raw : values) {
			String label = capitalizeFirst(normalizeNullable(raw));
			if (label == null) continue;
			String key = Normalizer.normalize(label, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
			if (normalized.add(key)) result.add(label);
		}
		return result;
	}

	private List<String> normalizeSpotifyIds(List<String> values) {
		if (values == null || values.isEmpty()) return new ArrayList<>();
		LinkedHashSet<String> result = new LinkedHashSet<>();
		for (String raw : values) {
			String id = normalizeNullable(raw);
			if (id == null || id.length() > 64) {
				throw new SoundConnectException(ErrorType.SPOTIFY_BAD_REQUEST);
			}
			result.add(id);
		}
		return new ArrayList<>(result);
	}

	private List<SpotifyTrackItemDto> hydrateSpotifyTracks(List<String> requestedIds) {
		if (requestedIds.isEmpty()) return new ArrayList<>();
		List<SpotifyTrackItemDto> resolved = spotifyService.getTracksByIds(requestedIds);
		Map<String, SpotifyTrackItemDto> byId = new LinkedHashMap<>();
		if (resolved != null) {
			for (SpotifyTrackItemDto track : resolved) {
				if (track != null && track.spotifyTrackId() != null) {
					byId.putIfAbsent(track.spotifyTrackId().strip(), track);
				}
			}
		}
		List<SpotifyTrackItemDto> ordered = new ArrayList<>(requestedIds.size());
		for (String requestedId : requestedIds) {
			SpotifyTrackItemDto track = byId.get(requestedId);
			if (track == null) {
				throw new SoundConnectException(
						ErrorType.SPOTIFY_NOT_FOUND,
						"One or more Spotify tracks could not be resolved"
				);
			}
			ordered.add(track);
		}
		return ordered;
	}

	private String normalizeNullable(String value) {
		if (value == null) return null;
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		return normalized.isEmpty() ? null : normalized;
	}

	private String normalizePhone(String rawPhone) {
		String normalized = normalizeNullable(rawPhone);
		if (normalized == null) return null;
		if (!PHONE_CHARACTERS.matcher(normalized).matches()) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "phone is invalid");
		}
		String digits = NON_PHONE_DIGITS.matcher(normalized).replaceAll("");
		if (digits.length() == 10 && digits.startsWith("5")) {
			digits = "0" + digits;
		} else if (digits.length() == 12 && digits.startsWith("90")) {
			digits = "0" + digits.substring(2);
		}
		if (digits.length() != 11 || !digits.startsWith("0")) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					"phone must contain 11 digits and start with 0");
		}
		return digits;
	}

	private String normalizeWebUrl(String rawValue, String fieldName) {
		String normalized = normalizeNullable(rawValue);
		if (normalized == null) return null;
		String candidate = URI_SCHEME.matcher(normalized).find()
				? normalized
				: "https://" + normalized;
		try {
			URI parsed = new URI(candidate).normalize();
			String scheme = parsed.getScheme() == null
					? ""
					: parsed.getScheme().toLowerCase(Locale.ROOT);
			if (!(scheme.equals("http") || scheme.equals("https"))
					|| parsed.getHost() == null
					|| parsed.getHost().isBlank()
					|| parsed.getUserInfo() != null) {
				throw new SoundConnectException(
						ErrorType.VALIDATION_ERROR,
						fieldName + " must be an absolute http(s) URL");
			}
			URI canonical = new URI(
					scheme, null, parsed.getHost().toLowerCase(Locale.ROOT), parsed.getPort(),
					parsed.getPath(), parsed.getQuery(), parsed.getFragment());
			String result = canonical.toASCIIString();
			if (result.length() > 255) {
				throw new SoundConnectException(
						ErrorType.VALIDATION_ERROR,
						fieldName + " can be at most 255 characters after normalization");
			}
			return result;
		} catch (URISyntaxException exception) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					fieldName + " must be an absolute http(s) URL");
		}
	}

	private String safeResponsePhone(String value, UUID profileId) {
		try {
			return normalizePhone(value);
		} catch (SoundConnectException exception) {
			log.warn("Unsafe legacy Studio phone omitted profileId={}", profileId);
			return null;
		}
	}

	private String safeResponseUrl(String value, String fieldName, UUID profileId) {
		try {
			return normalizeWebUrl(value, fieldName);
		} catch (SoundConnectException exception) {
			log.warn("Unsafe legacy Studio URL omitted profileId={} field={}", profileId, fieldName);
			return null;
		}
	}

	private String safeResponseTimeZone(String value, UUID profileId) {
		try {
			return value == null || value.isBlank()
					? DEFAULT_TIME_ZONE
					: ZoneId.of(value).getId();
		} catch (DateTimeException exception) {
			log.warn("Invalid legacy Studio time zone replaced profileId={}", profileId);
			return DEFAULT_TIME_ZONE;
		}
	}

	private String normalizeRequiredName(String value) {
		String normalized = normalizeNullable(value);
		if (normalized == null) {
			throw new SoundConnectException(ErrorType.BAD_REQUEST, "Studio name is required");
		}
		return normalized;
	}

	private String normalizeBusinessName(String value) {
		return UsernameUtils.normalize(normalizeRequiredName(value));
	}

	private String capitalizeFirst(String value) {
		if (value == null || value.isEmpty()) return value;
		int firstCodePoint = value.codePointAt(0);
		int firstLength = Character.charCount(firstCodePoint);
		String first = value.substring(0, firstLength).toUpperCase(TURKISH);
		return first + value.substring(firstLength);
	}
}
