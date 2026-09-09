package com.berkayb.soundconnect.modules.profile.MusicianProfile.service;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileActiveVenueDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.request.MusicianProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.dto.response.MusicianProfileSearchItemDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.mapper.MusicianProfileMapper;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileVenueRow;
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.profile.shared.ProfileInputValidation;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class MusicianProfileServiceImpl implements MusicianProfileService {
	
	private final MusicianProfileRepository musicianProfileRepository;
	private final UserEntityFinder userEntityFinder;
	private final InstrumentRepository instrumentRepository;
	private final MusicianProfileMapper musicianProfileMapper;
	private final BandService bandService;
	private final MediaAssetService mediaAssetService;
	private final PersonalProfileTypePolicy personalProfileTypePolicy;
	private final EntityManager entityManager;
	
	@Override
	public List<MusicianProfileSearchItemDto> searchProfiles(String query) { //eklendi
		String q = query == null ? "" : UsernameUtils.stripBoundaryWhitespace(query); //eklendi
		if (q.isEmpty()) return List.of(); //eklendi
		ProfileInputValidation.text(q, 100, "query");
		
		return musicianProfileRepository.searchByStageNameOrUsername(
				q,
				UsernameUtils.normalize(q),
				PageRequest.of(0, 10)
		) //eklendi
		                                .stream() //eklendi
		                                .limit(10) //eklendi
		                                .map(profile -> new MusicianProfileSearchItemDto( //eklendi
		                                                                                  profile.getId(), //eklendi
		                                                                                  profile.getUser().getId(), //eklendi
		                                                                                  profile.getUser().getUsername(), //eklendi
		                                                                                  profile.getStageName(), //eklendi
		                                                                                  resolveProfilePictureUrl(profile.getProfilePictureMediaId()) //eklendi
		                                )) //eklendi
		                                .toList(); //eklendi
	}
	
	@Override
	public MusicianProfile getProfileEntity(UUID profileId) {
		return musicianProfileRepository.findById(profileId)
		                                .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
	}
	
	@Override
	@Transactional
	public MusicianProfileResponseDto createProfile(UUID userId, MusicianProfileSaveRequestDto dto) {
		validateContent(dto);
		User user = personalProfileTypePolicy.lockAndAssertCanAcquire(
				userId, RoleEnum.ROLE_MUSICIAN);
		
		if (musicianProfileRepository.findByUserId(userId).isPresent()) {
			log.warn("Kullanici zaten bir profile sahip: {}", userId);
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		if (dto.profilePicture() != null) {
			mediaAssetService.validateAssignableMedia(
					userId, dto.profilePicture(), MediaOwnerType.USER, userId, MediaKind.IMAGE
			);
		}
		
		Set<Instrument> instruments = resolveInstruments(dto.instrumentIds());
		
		MusicianProfile profile = MusicianProfile.builder()
		                                         .user(user)
		                                         .stageName(dto.stageName())
		                                         .description(dto.description())
		                                         .profilePictureMediaId(dto.profilePicture())
		                                         .instagramUrl(ProfileInputValidation.webUrl(dto.instagramUrl(), "instagramUrl"))
		                                         .youtubeUrl(ProfileInputValidation.webUrl(dto.youtubeUrl(), "youtubeUrl"))
		                                         .soundcloudUrl(ProfileInputValidation.webUrl(dto.soundcloudUrl(), "soundcloudUrl"))
		                                         .spotifyEmbedUrl(ProfileInputValidation.webUrl(dto.spotifyEmbedUrl(), "spotifyEmbedUrl"))
		                                         .instruments(instruments)
		                                         .spotifyArtistId(ProfileInputValidation.optionalIdentifier(dto.spotifyArtistId()))
		                                         .spotifyTrackIds(dto.spotifyTrackIds() != null ? dto.spotifyTrackIds() : List.of())
		                                         .spotifyTracks(dto.spotifyTracks() != null ? dto.spotifyTracks() : List.of())
		                                         .build();
		
		MusicianProfile saved = musicianProfileRepository.save(profile);
		
		log.info("Yeni muzisyen profili olusturuldu. UserId: {}", userId);
		
		var base = musicianProfileMapper.toDto(saved);
		String profilePictureUrl = resolveProfilePictureUrl(saved.getProfilePictureMediaId());
		var bands = new HashSet<>(bandService.getBandsByUser(userId));
		var activeVenues = activeVenueConnections(saved);
		
		return new MusicianProfileResponseDto(
				base.id(),
				saved.getUser().getId(),
				saved.getUser().getUsername(),
				base.stageName(),
				base.bio(),
				base.profilePictureMediaId(),
				profilePictureUrl,
				base.instagramUrl(),
				base.youtubeUrl(),
				base.soundcloudUrl(),
				base.spotifyEmbedUrl(),
				base.spotifyArtistId(),
				base.instruments(),
				publicVenueNames(activeVenues),
				activeVenues,
				bands,
				base.spotifyTrackIds(),
				base.spotifyTracks()
		);
	}
	
	@Override
	public MusicianProfileResponseDto getProfileByUserId(UUID userId) {
		userEntityFinder.getUser(userId);
		
		MusicianProfile profile = musicianProfileRepository.findByUserId(userId)
		                                                   .orElseThrow(() -> {
			                                                   log.warn("Profil bulunamadi. UserId: {}", userId);
			                                                   return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
		                                                   });
		
		log.info("Musician profile getirildi. UserId: {}", userId);
		
		var base = musicianProfileMapper.toDto(profile);
		String profilePictureUrl = resolveProfilePictureUrl(profile.getProfilePictureMediaId());
		var bands = new HashSet<>(bandService.getBandsByUser(userId));
		var activeVenues = activeVenueConnections(profile);
		
		return new MusicianProfileResponseDto(
				base.id(),
				profile.getUser().getId(),
				profile.getUser().getUsername(),
				base.stageName(),
				base.bio(),
				base.profilePictureMediaId(),
				profilePictureUrl,
				base.instagramUrl(),
				base.youtubeUrl(),
				base.soundcloudUrl(),
				base.spotifyEmbedUrl(),
				base.spotifyArtistId(),
				base.instruments(),
				publicVenueNames(activeVenues),
				activeVenues,
				bands,
				base.spotifyTrackIds(),
				base.spotifyTracks()
		);
	}
	
	@Override
	@Transactional
	public MusicianProfileResponseDto updateProfile(UUID userId, MusicianProfileSaveRequestDto dto) {
		validateContent(dto);
		userEntityFinder.getUser(userId);
		
		MusicianProfile profile = musicianProfileRepository.findByUserIdForUpdate(userId)
		                                                   .orElseThrow(() -> {
			                                                   log.warn("Profil bulunamadi. UserId: {}", userId);
			                                                   return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND);
			                                                   });
		// A request may already have loaded this profile through the user relation.
		// Acquiring the row lock does not refresh that managed instance; reload it
		// under the lock before applying a partial update to preserve newer fields.
		entityManager.refresh(profile, LockModeType.PESSIMISTIC_WRITE);
		
		if (dto.stageName() != null) profile.setStageName(dto.stageName());
		if (dto.description() != null) profile.setDescription(dto.description());
		if (dto.profilePicture() != null) {
			mediaAssetService.validateAssignableMedia(
					userId, dto.profilePicture(), MediaOwnerType.MUSICIAN_PROFILE, profile.getId(), MediaKind.IMAGE
			);
			profile.setProfilePictureMediaId(dto.profilePicture());
		}
		if (dto.instagramUrl() != null) profile.setInstagramUrl(ProfileInputValidation.webUrl(dto.instagramUrl(), "instagramUrl"));
		if (dto.youtubeUrl() != null) profile.setYoutubeUrl(ProfileInputValidation.webUrl(dto.youtubeUrl(), "youtubeUrl"));
		if (dto.soundcloudUrl() != null) profile.setSoundcloudUrl(ProfileInputValidation.webUrl(dto.soundcloudUrl(), "soundcloudUrl"));
		if (dto.spotifyEmbedUrl() != null) profile.setSpotifyEmbedUrl(ProfileInputValidation.webUrl(dto.spotifyEmbedUrl(), "spotifyEmbedUrl"));
		if (dto.spotifyArtistId() != null) profile.setSpotifyArtistId(ProfileInputValidation.optionalIdentifier(dto.spotifyArtistId()));
		if (dto.spotifyTrackIds() != null) profile.setSpotifyTrackIds(dto.spotifyTrackIds());
		if (dto.spotifyTracks() != null) profile.setSpotifyTracks(dto.spotifyTracks());
		
		if (dto.instrumentIds() != null) {
			profile.setInstruments(resolveInstruments(dto.instrumentIds()));
		}
		
		MusicianProfile updated = musicianProfileRepository.save(profile);
		
		log.info("Musician profile güncellendi. UserId: {}", userId);
		
		var base = musicianProfileMapper.toDto(updated);
		String profilePictureUrl = resolveProfilePictureUrl(updated.getProfilePictureMediaId());
		var bands = new HashSet<>(bandService.getBandsByUser(userId));
		var activeVenues = activeVenueConnections(updated);
		
		return new MusicianProfileResponseDto(
				base.id(),
				updated.getUser().getId(),
				updated.getUser().getUsername(),
				base.stageName(),
				base.bio(),
				base.profilePictureMediaId(),
				profilePictureUrl,
				base.instagramUrl(),
				base.youtubeUrl(),
				base.soundcloudUrl(),
				base.spotifyEmbedUrl(),
				base.spotifyArtistId(),
				base.instruments(),
				publicVenueNames(activeVenues),
				activeVenues,
				bands,
				base.spotifyTrackIds(),
				base.spotifyTracks()
		);
	}
	
	@Override
	public MusicianProfileResponseDto getProfileByProfileId(UUID profileId) { //degisti
		MusicianProfile profile = musicianProfileRepository.findById(profileId) //degisti
		                                                   .orElseThrow(() -> { //degisti
			                                                   log.warn("Profil bulunamadi. profileId: {}", profileId); //degisti
			                                                   return new SoundConnectException(ErrorType.PROFILE_NOT_FOUND); //degisti
		                                                   }); //degisti
		
		var base = musicianProfileMapper.toDto(profile); //degisti
		String profilePictureUrl = resolveProfilePictureUrl(profile.getProfilePictureMediaId()); //degisti
		var bands = new HashSet<>(bandService.getBandsByUser(profile.getUser().getId())); //degisti
		var activeVenues = activeVenueConnections(profile);
		
		return new MusicianProfileResponseDto( //degisti
		                                       base.id(), //degisti
		                                       profile.getUser().getId(), //degisti
		                                       profile.getUser().getUsername(), //degisti
		                                       base.stageName(), //degisti
		                                       base.bio(), //degisti
		                                       base.profilePictureMediaId(), //degisti
		                                       profilePictureUrl, //degisti
		                                       base.instagramUrl(), //degisti
		                                       base.youtubeUrl(), //degisti
		                                       base.soundcloudUrl(), //degisti
		                                       base.spotifyEmbedUrl(), //degisti
		                                       base.spotifyArtistId(), //degisti
		                                       base.instruments(), //degisti
		                                       publicVenueNames(activeVenues),
		                                       activeVenues,
		                                       bands, //degisti
		                                       base.spotifyTrackIds(), //degisti
		                                       base.spotifyTracks() //degisti
		); //degisti
	}
	
	
	private void validateContent(MusicianProfileSaveRequestDto dto) {
		if (dto == null) throw ProfileInputValidation.invalid("Profile content is required");
		ProfileInputValidation.text(dto.stageName(), 255, "stageName");
		ProfileInputValidation.text(dto.description(), 1024, "description");
		ProfileInputValidation.collection(dto.instrumentIds(), 50, "instrumentIds");
		ProfileInputValidation.trackIds(dto.spotifyTrackIds());
		ProfileInputValidation.collection(dto.spotifyTracks(), 50, "spotifyTracks");
		ProfileInputValidation.optionalIdentifier(dto.spotifyArtistId());
		ProfileInputValidation.webUrl(dto.instagramUrl(), "instagramUrl");
		ProfileInputValidation.webUrl(dto.youtubeUrl(), "youtubeUrl");
		ProfileInputValidation.webUrl(dto.soundcloudUrl(), "soundcloudUrl");
		ProfileInputValidation.webUrl(dto.spotifyEmbedUrl(), "spotifyEmbedUrl");
	}

	private Set<Instrument> resolveInstruments(Set<UUID> instrumentIds) {
		if (instrumentIds == null || instrumentIds.isEmpty()) return new HashSet<>();
		Set<Instrument> instruments = new HashSet<>(instrumentRepository.findAllById(instrumentIds));
		if (instruments.size() != instrumentIds.size()) {
			throw new SoundConnectException(ErrorType.INSTRUMENT_NOT_FOUND);
		}
		return instruments;
	}

	private List<MusicianProfileActiveVenueDto> activeVenueConnections(MusicianProfile profile) {
		if (profile == null || profile.getId() == null) return List.of();
		List<MusicianProfileVenueRow> venues = musicianProfileRepository.findPublicVenueConnections(profile.getId());
		List<UUID> mediaIds = venues.stream().map(MusicianProfileVenueRow::profilePictureMediaId)
				.filter(Objects::nonNull).distinct().toList();
		Map<UUID, String> urls = resolveVenueImageUrls(mediaIds);
		return venues.stream().map(venue -> new MusicianProfileActiveVenueDto(
				venue.venueId(), venue.venueName(), venue.profilePictureMediaId() == null
						? null : urls.get(venue.profilePictureMediaId()))).toList();
	}

	private Set<String> publicVenueNames(List<MusicianProfileActiveVenueDto> venues) {
		return venues.stream().map(MusicianProfileActiveVenueDto::venueName)
				.collect(java.util.stream.Collectors.toSet());
	}

	private Map<UUID, String> resolveVenueImageUrls(List<UUID> mediaIds) {
		if (mediaIds.isEmpty()) return Map.of();
		try {
			Map<UUID, String> urls = mediaAssetService.getDisplayUrlMap(mediaIds);
			return urls == null ? Map.of() : urls;
		} catch (RuntimeException exception) {
			log.warn("Connected venue image batch unavailable. assetCount={}", mediaIds.size());
			return Map.of();
		}
	}

	private String resolveProfilePictureUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) return null;
		try {
			return mediaAssetService.getDisplayUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("Profile picture resolve failed. mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
}
