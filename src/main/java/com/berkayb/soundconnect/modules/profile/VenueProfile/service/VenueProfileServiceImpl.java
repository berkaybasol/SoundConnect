package com.berkayb.soundconnect.modules.profile.VenueProfile.service;

import com.berkayb.soundconnect.modules.event.entity.Event; //eklendi
import com.berkayb.soundconnect.modules.event.support.EventPosterResolver;
import com.berkayb.soundconnect.modules.event.support.EventScheduleClock;
import com.berkayb.soundconnect.modules.event.repository.EventRepository; //eklendi
import com.berkayb.soundconnect.modules.event.enums.PerformerType; //eklendi
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile; //eklendi
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.*;
import com.berkayb.soundconnect.modules.profile.VenueProfile.entity.VenueProfile;
import com.berkayb.soundconnect.modules.profile.VenueProfile.mapper.VenueProfileMapper;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate; //eklendi
import java.util.Comparator; //eklendi
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class VenueProfileServiceImpl implements VenueProfileService {
	
	private final VenueProfileRepository venueProfileRepository;
	private final VenueRepository venueRepository;
	private final VenueProfileMapper venueProfileMapper;
	private final VenueEntityFinder venueEntityFinder;
	private final EventRepository eventRepository;
	private final MediaAssetService mediaAssetService;
	private final MediaAssetRepository mediaAssetRepository;
	private final EventScheduleClock scheduleClock;
	
	@Override
	public List<VenueProfileResponseDto> getProfilesByUserId(UUID userId) {
		List<Venue> venues = venueRepository.findAllByOwnerId(userId);
		
		if (venues.isEmpty()) {
			return List.of();
		}
		
		return venues.stream()
		             .map(venue -> venueProfileRepository.findByVenueId(venue.getId())
		                                                 .map(venueProfileMapper::toResponse)
		                                                 .orElse(null))
		             .filter(Objects::nonNull)
		             .collect(Collectors.toList());
	}
	
	@Override
	@Transactional
	public VenueProfileResponseDto updateProfileByVenueId(UUID userId, UUID venueId, VenueProfileSaveRequestDto dto) {
		Venue venue = venueRepository.findByIdAndOwnerId(venueId, userId)
		                             .orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_NOT_FOUND));
		VenueProfile profile = venueProfileRepository.findByVenueId(venue.getId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		applyOwnerUpdate(userId, profile, dto);
		return venueProfileMapper.toResponse(venueProfileRepository.save(profile));
	}
	
	@Override
	@Transactional
	public VenueProfileResponseDto createProfile(UUID venueId, VenueProfileSaveRequestDto dto) {
		Venue venue = venueEntityFinder.getVenue(venueId);
		
		if (venueProfileRepository.findByVenueId(venueId).isPresent()) {
			throw new SoundConnectException(ErrorType.PROFILE_ALREADY_EXISTS);
		}
		if (dto.profilePicture() != null) {
			lockAssignableVenueImage(dto.profilePicture(), MediaOwnerType.VENUE, venueId);
		}
		
		VenueProfile profile = VenueProfile.builder()
		                                   .venue(venue)
		                                   .bio(dto.bio())
		                                   .profilePictureMediaId(dto.profilePicture())
		                                   .instagramUrl(dto.instagramUrl())
		                                   .youtubeUrl(dto.youtubeUrl())
		                                   .websiteUrl(dto.websiteUrl())
		                                   .build();
		
		VenueProfile saved = venueProfileRepository.save(profile);
		return venueProfileMapper.toResponse(saved);
	}
	
	@Override
	public VenueProfileResponseDto getProfileByVenueId(UUID venueId) {
		VenueProfile profile = venueProfileRepository.findByVenueId(venueId)
		                                             .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		return venueProfileMapper.toResponse(profile);
	}
	
	@Override
	@Transactional
	public VenueProfileResponseDto updateProfile(UUID venueId, VenueProfileSaveRequestDto dto) {
		VenueProfile profile = venueProfileRepository.findByVenueId(venueId)
		                                             .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND));
		
		if (dto.bio() != null) profile.setBio(dto.bio());
		if (dto.profilePicture() != null) {
			lockAssignableVenueImage(
					dto.profilePicture(), MediaOwnerType.VENUE_PROFILE, profile.getId());
			profile.setProfilePictureMediaId(dto.profilePicture());
		}
		if (dto.instagramUrl() != null) profile.setInstagramUrl(dto.instagramUrl());
		if (dto.youtubeUrl() != null) profile.setYoutubeUrl(dto.youtubeUrl());
		if (dto.websiteUrl() != null) profile.setWebsiteUrl(dto.websiteUrl());
		
		VenueProfile updated = venueProfileRepository.save(profile);
		return venueProfileMapper.toResponse(updated);
	}
	
	@Override
	public VenueOwnerProfileResponseDto getOwnerProfileDetail(UUID userId, UUID venueId) { //eklendi
		Venue venue = venueRepository.findByIdAndOwnerId(venueId, userId) //eklendi
		                             .orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_NOT_FOUND)); //eklendi
		
		VenueProfile profile = venueProfileRepository.findByVenueId(venueId) //eklendi
		                                             .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND)); //eklendi
		
		return toOwnerProfileDetail(venue, profile); //eklendi
	}
	
	@Override
	@Transactional
	public VenueOwnerProfileResponseDto updateOwnerProfileDetail(UUID userId, UUID venueId, VenueProfileSaveRequestDto dto) { //eklendi
		Venue venue = venueRepository.findByIdAndOwnerId(venueId, userId) //eklendi
		                             .orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_NOT_FOUND)); //eklendi
		
		VenueProfile profile = venueProfileRepository.findByVenueId(venueId) //eklendi
		                                             .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND)); //eklendi
		
		applyOwnerUpdate(userId, profile, dto);
		
		VenueProfile updated = venueProfileRepository.save(profile); //eklendi
		return toOwnerProfileDetail(venue, updated); //eklendi
	}

	private void applyOwnerUpdate(UUID userId, VenueProfile profile, VenueProfileSaveRequestDto dto) {
		if (dto.bio() != null) profile.setBio(dto.bio());
		if (dto.profilePicture() != null) {
			mediaAssetService.validateAssignableMedia(
					userId, dto.profilePicture(), MediaOwnerType.VENUE_PROFILE,
					profile.getId(), MediaKind.IMAGE
			);
			profile.setProfilePictureMediaId(dto.profilePicture());
		}
		if (dto.instagramUrl() != null) profile.setInstagramUrl(dto.instagramUrl());
		if (dto.youtubeUrl() != null) profile.setYoutubeUrl(dto.youtubeUrl());
		if (dto.websiteUrl() != null) profile.setWebsiteUrl(dto.websiteUrl());
	}

	private void lockAssignableVenueImage(UUID assetId, MediaOwnerType ownerType, UUID ownerId) {
		MediaAsset asset = mediaAssetRepository.findByIdForUpdate(assetId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		if (asset.getKind() != MediaKind.IMAGE) {
			throw new SoundConnectException(ErrorType.MEDIA_KIND_INVALID);
		}
		if (asset.getStatus() != MediaStatus.READY) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
		}
		if (asset.getVisibility() != MediaVisibility.PUBLIC) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_PUBLIC);
		}
		if (asset.getOwnerType() != ownerType || !ownerId.equals(asset.getOwnerId())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_OWNER_MISMATCH);
		}
		if (!StringUtils.hasText(asset.getPlaybackUrl()) && !StringUtils.hasText(asset.getSourceUrl())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_STATE_INVALID);
		}
	}
	
	@Override
	public VenuePublicProfileResponseDto getPublicProfileDetail(UUID venueId) { //eklendi
		Venue venue = venueEntityFinder.getVenue(venueId); //eklendi
		
		VenueProfile profile = venueProfileRepository.findByVenueId(venueId) //eklendi
		                                             .orElseThrow(() -> new SoundConnectException(ErrorType.PROFILE_NOT_FOUND)); //eklendi
		
		return toPublicProfileDetail(venue, profile); //eklendi
	}
	
	private VenueOwnerProfileResponseDto toOwnerProfileDetail(Venue venue, VenueProfile profile) { //eklendi
		return new VenueOwnerProfileResponseDto(
				profile.getId(),
				venue.getId(),
				venue.getOwner() != null ? venue.getOwner().getId() : null,
				
				venue.getName(),
				profile.getBio(),
				
				profile.getProfilePictureMediaId(),
				resolveProfilePictureUrl(profile.getProfilePictureMediaId()),
				
				profile.getInstagramUrl(),
				profile.getYoutubeUrl(),
				profile.getWebsiteUrl(),
				
				venue.getAddress(),
				venue.getPhone(),
				venue.getWebsite(),
				venue.getDescription(),
				venue.getMusicStartTime(),
				
				venue.getCity() != null ? venue.getCity().getId() : null,
				venue.getCity() != null ? venue.getCity().getName() : null,
				venue.getDistrict() != null ? venue.getDistrict().getId() : null,
				venue.getDistrict() != null ? venue.getDistrict().getName() : null,
				venue.getNeighborhood() != null ? venue.getNeighborhood().getId() : null,
				venue.getNeighborhood() != null ? venue.getNeighborhood().getName() : null,
				
				venue.getStatus(),
				
				mapActiveMusicians(venue),
				mapActiveBands(venue), //eklendi
				mapWeeklyEvents(venue)
		
		);
	}
	
	private VenuePublicProfileResponseDto toPublicProfileDetail(Venue venue, VenueProfile profile) { //eklendi
		return new VenuePublicProfileResponseDto(
				profile.getId(),
				venue.getId(),
				venue.getOwner() != null ? venue.getOwner().getId() : null,
				
				venue.getName(),
				profile.getBio(),
				
				resolveProfilePictureUrl(profile.getProfilePictureMediaId()),
				
				profile.getInstagramUrl(),
				profile.getYoutubeUrl(),
				profile.getWebsiteUrl(),
				
				venue.getAddress(),
				venue.getPhone(),
				venue.getWebsite(),
				venue.getDescription(),
				venue.getMusicStartTime(),
				
				venue.getCity() != null ? venue.getCity().getName() : null,
				venue.getDistrict() != null ? venue.getDistrict().getName() : null,
				venue.getNeighborhood() != null ? venue.getNeighborhood().getName() : null,
				
				mapActiveMusicians(venue),
				mapActiveBands(venue),
				mapWeeklyEvents(venue)
		);
	}
	
	private List<VenueActiveMusicianDto> mapActiveMusicians(Venue venue) { //eklendi
		if (venue.getActiveMusicians() == null || venue.getActiveMusicians().isEmpty()) { //eklendi
			return List.of(); //eklendi
		}
		
		return venue.getActiveMusicians()
		            .stream()
		            .sorted(Comparator.comparing(mp -> safeText(mp.getStageName())))
		            .map(this::toActiveMusicianDto)
		            .toList();
	}
	
	private VenueActiveMusicianDto toActiveMusicianDto(MusicianProfile profile) { //eklendi
		return new VenueActiveMusicianDto(
				profile.getId(),
				resolveMusicianDisplayName(profile),
				resolveMusicianProfileImageUrl(profile)
		);
	}
	
	private String resolveMusicianDisplayName(MusicianProfile profile) { //eklendi
		if (profile.getStageName() != null && !profile.getStageName().isBlank()) { //eklendi
			return profile.getStageName(); //eklendi
		}
		if (profile.getUser() != null && profile.getUser().getUsername() != null && !profile.getUser().getUsername().isBlank()) { //eklendi
			return profile.getUser().getUsername(); //eklendi
		}
		return "Bilinmeyen Muzisyen"; //eklendi
	}
	
	private String resolveMusicianProfileImageUrl(MusicianProfile profile) { //degisti
		if (profile == null || profile.getProfilePictureMediaId() == null) return null; //degisti
		try { //degisti
			return mediaAssetService.getDisplayUrl(profile.getProfilePictureMediaId()); //degisti
		} catch (Exception e) { //degisti
			log.warn("Musician profile picture resolve failed. musicianProfileId={}, mediaAssetId={}", //degisti
			         profile.getId(), profile.getProfilePictureMediaId()); //degisti
			return null; //degisti
		} //degisti
	}
	
	private List<VenueActiveBandDto> mapActiveBands(Venue venue) { //eklendi
		if (venue.getActiveBands() == null || venue.getActiveBands().isEmpty()) { //eklendi
			return List.of(); //eklendi
		} //eklendi
		
		return venue.getActiveBands() //eklendi
		            .stream() //eklendi
		            .sorted(Comparator.comparing(b -> safeText(b.getName()))) //eklendi
		            .map(this::toActiveBandDto) //eklendi
		            .toList(); //eklendi
	} //eklendi
	
	private VenueActiveBandDto toActiveBandDto(Band band) { //eklendi
		return new VenueActiveBandDto( //eklendi
		                               band.getId(), //eklendi
		                               resolveBandDisplayName(band), //eklendi
		                               resolveBandProfileImageUrl(band) //eklendi
		); //eklendi
	} //eklendi
	
	private String resolveBandDisplayName(Band band) { //eklendi
		if (band.getName() != null && !band.getName().isBlank()) { //eklendi
			return band.getName(); //eklendi
		} //eklendi
		return "Band"; //eklendi
	} //eklendi
	
	private String resolveBandProfileImageUrl(Band band) { //eklendi
		if (band == null || band.getProfilePictureMediaId() == null) return null; //eklendi
		try { //eklendi
			return mediaAssetService.getDisplayUrl(band.getProfilePictureMediaId()); //eklendi
		} catch (Exception e) { //eklendi
			log.warn("Band profile picture resolve failed. bandId={}, mediaAssetId={}", //eklendi
			         band.getId(), band.getProfilePictureMediaId()); //eklendi
			return null; //eklendi
		} //eklendi
	} //eklendi
	
	
	private List<VenueEventSummaryDto> mapWeeklyEvents(Venue venue) { //degisti
		// Weekly profiles follow the event's Istanbul calendar day. An event
		// that ended earlier today remains until the next business date.
		LocalDate today = scheduleClock.localNow().toLocalDate();
		LocalDate endDate = today.plusDays(6); //degisti
		
		return eventRepository.findByVenueAndEventDateBetweenOrderByEventDateAscStartTimeAsc(venue, today, endDate) //degisti
		                      .stream()
		                      .map(this::toVenueEventSummaryDto)
		                      .toList();
	}
	
	
	private VenueEventSummaryDto toVenueEventSummaryDto(Event event) { //eklendi
		return new VenueEventSummaryDto(
				event.getId(),
				event.getTitle(),
				EventPosterResolver.resolve(event.getPosterImage(), mediaAssetService),
				resolvePerformerName(event),
				event.getMusicianProfile() != null ? event.getMusicianProfile().getId() : null, //eklendi
				event.getBand() != null ? event.getBand().getId() : null,
				resolvePerformerType(event),
				event.getEventDate(),
				event.getStartTime(),
				event.getEndTime()
		);
	}
	
	
	private String resolvePerformerName(Event event) { //eklendi
		if (event.getBand() != null && event.getBand().getName() != null && !event.getBand().getName().isBlank()) { //eklendi
			return event.getBand().getName(); //eklendi
		}
		if (event.getMusicianProfile() != null) {
			if (event.getMusicianProfile().getUser() != null
					&& event.getMusicianProfile().getUser().getUsername() != null
					&& !event.getMusicianProfile().getUser().getUsername().isBlank()) {
				return event.getMusicianProfile().getUser().getUsername();
			}
			if (event.getMusicianProfile().getStageName() != null && !event.getMusicianProfile().getStageName().isBlank()) {
				return event.getMusicianProfile().getStageName();
			}
		}
		if (event.getManualPerformerName() != null && !event.getManualPerformerName().isBlank()) {
			return event.getManualPerformerName();
		}
		return "Belirtilmemiş";
	}
	
	private PerformerType resolvePerformerType(Event event) { //eklendi
		if (event.getBand() != null) { //eklendi
			return PerformerType.BAND; //eklendi
		}
		if (event.getMusicianProfile() != null) {
			return PerformerType.MUSICIAN;
		}
		if (event.getManualPerformerName() != null && !event.getManualPerformerName().isBlank()) {
			return PerformerType.MANUAL;
		}
		return null;
	}
	
	private String resolveProfilePictureUrl(UUID mediaId) { //degisti
		if (mediaId == null) return null; //degisti
		try { //degisti
			return mediaAssetService.getDisplayUrl(mediaId); //degisti
		} catch (Exception e) { //degisti
			log.warn("Venue profile picture resolve failed. mediaAssetId={}", mediaId); //degisti
			return null; //degisti
		} //degisti
	}
	
	private String safeText(String value) { //eklendi
		return value == null ? "" : value; //eklendi
	}
}
