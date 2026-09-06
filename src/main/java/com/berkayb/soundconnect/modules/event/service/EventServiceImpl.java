package com.berkayb.soundconnect.modules.event.service;

import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.enums.EventPerformerApprovalStatus;
import com.berkayb.soundconnect.modules.event.enums.EventOrigin;
import com.berkayb.soundconnect.modules.event.performer.entity.EventPerformerRequest;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventServiceImpl implements EventService{
	
	private final EventRepository eventRepository;
	private final VenueEntityFinder venueEntityFinder;
	private final UserEntityFinder userEntityFinder;
	private final MusicianProfileService musicianProfileService;
	private final EventMapper eventMapper;
	private final MediaAssetRepository mediaAssetRepository;
	private final VenueProfileRepository venueProfileRepository;
	private final MusicianProfileRepository musicianProfileRepository;
	private final BandRepository bandRepository;
	private final VenueRepository venueRepository;
	private final EventPerformerRequestService eventPerformerRequestService;
	
	@Override
	public List<EventResponseDto> getWeeklyEventsByVenue(UUID venueId, LocalDate startDate, LocalDate endDate) {
		Venue venue = venueEntityFinder.getVenue(venueId);
		
		if (startDate == null || endDate == null || endDate.isBefore(startDate)) {
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		return eventRepository.findByVenueAndEventDateBetweenOrderByEventDateAscStartTimeAsc(venue, startDate, endDate)
				.stream()
				.map(eventMapper::toDto)
				.toList();
	}
	
	@Override
	public List<EventResponseDto> getOwnerEventsByVenue(UUID ownerUserId, UUID venueId) {
		User owner = userEntityFinder.getUser(ownerUserId);
		Venue venue = venueEntityFinder.getVenue(venueId);
		
		if (venue.getOwner() == null || !venue.getOwner().getId().equals(owner.getId())) {
			log.warn("[EVENT] Kullanici bu venue'nun sahibi degil. userId={}, venueId={}", ownerUserId, venueId);
			throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
		}
		return eventRepository.findByVenueOrderByEventDateAscStartTimeAsc(venue)
		                      .stream()
		                      .map(eventMapper::toDto)
		                      .toList();
		
	}
	
	@Override
	@Transactional
	public EventResponseDto createEvent(UUID createdByUserId, EventCreateRequestDto dto) {
		log.info("[EVENT] Yeni etkinlik oluşturma isteği alındı. title={}", dto.title());
		
		// eventi olusturan kullaniciyi dogrula
		User createdByUser = userEntityFinder.getUser(createdByUserId);
		
		// Venue dogru mu?
		Venue venue = venueEntityFinder.getVenue(dto.venueId());
		
		// eventi sadece ilgili venue sahibi olusturabilsin
		if (venue.getOwner() == null || !venue.getOwner().getId().equals(createdByUserId)) {
			log.warn("[EVENT] Kullanici bu venue'nun sahibi degil. userId={}, venueId={}",
			         createdByUserId, dto.venueId());
			throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
		}
		
		// sadece onayli mekanlar event olusturabilsin
		if (!VenueStatus.APPROVED.equals(venue.getStatus())) {
			log.warn("[EVENT] Onaysiz mekan event olusturmaya calisti. venueId={}, status={}",
			         venue.getId(), venue.getStatus());
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		String normalizedPosterImage = normalizeAndValidatePosterReference(dto.posterImage(), venue);
		
		// Performer dogrulamasi
		boolean musicianProvided = dto.musicianProfileId() != null;
		boolean bandProvided = dto.bandId() != null;
		boolean manualProvided = dto.manualPerformerName() != null && !dto.manualPerformerName().isBlank();
		
		int providedCount = 0;
		if (musicianProvided) providedCount++;
		if (bandProvided) providedCount++;
		if (manualProvided) providedCount++;
		
		if (providedCount > 1) {
			throw new SoundConnectException(ErrorType.INVALID_PERFORMER_SELECTION);
		}
		
		// saat araligi dogrulamasi
		if (dto.endTime() != null && dto.endTime().isBefore(dto.startTime())) {
			log.warn("[EVENT] Gecersiz saat araligi. startTime={}, endTime={}",
			         dto.startTime(), dto.endTime());
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		
		MusicianProfile musician = null;
		Band band = null;
		MusicianProfile requestedMusician = null;
		Band requestedBand = null;
		String effectiveManualPerformerName = manualProvided ? dto.manualPerformerName().trim() : null;
		EventPerformerApprovalStatus approvalStatus = EventPerformerApprovalStatus.NOT_REQUIRED;
		
		// A selected profile becomes publicly linked only when an active connection
		// exists. Otherwise it keeps only a name snapshot. Both paths still ask
		// for explicit event-scoped permission before publishing on a profile.
		if (musicianProvided) {
			requestedMusician = musicianProfileService.getProfileEntity(dto.musicianProfileId());
			if (musicianProfileRepository.lockActiveVenueConnection(requestedMusician.getId(), venue.getId()).isPresent()) {
				musician = requestedMusician;
				approvalStatus = EventPerformerApprovalStatus.APPROVED;
			} else {
				effectiveManualPerformerName = displayMusicianName(requestedMusician);
				approvalStatus = EventPerformerApprovalStatus.PENDING;
			}
		} else if (bandProvided) {
			// Serialize selection with band deletion. The same band row is locked by
			// deletion and by a later consent decision before either touches events.
			requestedBand = bandRepository.findByIdForUpdate(dto.bandId())
					.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND));
			if (venueRepository.lockActiveBandConnection(venue.getId(), requestedBand.getId()).isPresent()) {
				band = requestedBand;
				approvalStatus = EventPerformerApprovalStatus.APPROVED;
			} else {
				effectiveManualPerformerName = validatedPerformerSnapshot(requestedBand.getName());
				approvalStatus = EventPerformerApprovalStatus.PENDING;
			}
		}
		
		// event olustur
		Event event = Event.builder()
				.eventOrigin(EventOrigin.VENUE)
				.organizerUserId(createdByUserId)
				.title(dto.title())
				.description(dto.description())
				.eventDate(dto.eventDate())
				.startTime(dto.startTime())
				.endTime(dto.endTime())
				.posterImage(normalizedPosterImage)
				.venue(venue)
				.musicianProfile(musician)
				.band(band)
				.manualPerformerName(effectiveManualPerformerName)
				.performerApprovalStatus(approvalStatus)
				.profileCalendarApproved(false)
				.build();
		
		Event saved = eventRepository.save(event);
		if (approvalStatus == EventPerformerApprovalStatus.PENDING) {
			eventPerformerRequestService.createPendingRequest(createdByUserId, saved, requestedMusician, requestedBand);
		} else if (requestedMusician != null || requestedBand != null) {
			eventPerformerRequestService.createProfileVisibilityRequest(
					createdByUserId, saved, requestedMusician, requestedBand);
		}
		
		log.info("Event created succesfully: {}", saved.getId());
		
		return eventMapper.toDto(saved);
		
	}

	private String displayMusicianName(MusicianProfile musician) {
		if (musician.getUser() != null && StringUtils.hasText(musician.getUser().getUsername())) {
			return validatedPerformerSnapshot(musician.getUser().getUsername());
		}
		if (StringUtils.hasText(musician.getStageName())) {
			return validatedPerformerSnapshot(musician.getStageName());
		}
		throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
	}

	private String validatedPerformerSnapshot(String value) {
		if (!StringUtils.hasText(value)) {
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		String normalized = value.trim();
		if (normalized.length() > EventPerformerRequest.PERFORMER_NAME_SNAPSHOT_MAX_LENGTH) {
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
		return normalized;
	}

	private String normalizeAndValidatePosterReference(String posterImage, Venue venue) {
		if (!StringUtils.hasText(posterImage)) {
			return posterImage;
		}
		final UUID assetId;
		try {
			assetId = UUID.fromString(posterImage.trim());
		} catch (IllegalArgumentException legacyUrlOrPath) {
			// Event posters historically accepted a direct URL/path. Only UUID-backed
			// posters participate in the MediaAsset lifecycle fence.
			return posterImage;
		}

		MediaAsset asset = mediaAssetRepository.findByIdForUpdate(assetId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		UUID venueProfileId = venueProfileRepository.findByVenueId(venue.getId())
				.map(profile -> profile.getId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_OWNER_MISMATCH));
		if (asset.getKind() != MediaKind.IMAGE) {
			throw new SoundConnectException(ErrorType.MEDIA_KIND_INVALID);
		}
		if (asset.getStatus() != MediaStatus.READY) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
		}
		if (asset.getVisibility() != MediaVisibility.PUBLIC) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_PUBLIC);
		}
		if (asset.getOwnerType() != MediaOwnerType.VENUE_PROFILE
				|| !venueProfileId.equals(asset.getOwnerId())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_OWNER_MISMATCH);
		}
		if (!StringUtils.hasText(asset.getPlaybackUrl()) && !StringUtils.hasText(asset.getSourceUrl())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_STATE_INVALID);
		}
		return assetId.toString();
	}
	
	@Override
	@Transactional
	public void deleteEventById(UUID deletedByUserId, UUID eventId) {
		// degistirildi: event'i silen kullaniciyi dogrula
		User deletedByUser = userEntityFinder.getUser(deletedByUserId);
		
		Event event = eventRepository.findByIdForUpdate(eventId)
		                             .orElseThrow(() -> new SoundConnectException(ErrorType.EVENT_NOT_FOUND));
		
		// degistirildi: sadece event'in bagli oldugu venue'nun sahibi silebilsin
		if (event.getEventOrigin() != EventOrigin.VENUE || event.getVenue() == null || event.getVenue().getOwner() == null ||
				!event.getVenue().getOwner().getId().equals(deletedByUser.getId())) {
			log.warn("[EVENT] Kullanici bu event'i silme yetkisine sahip degil. userId={}, eventId={}",
			         deletedByUserId, eventId);
			throw new SoundConnectException(ErrorType.EVENT_NOT_FOUND);
		}
		
		eventPerformerRequestService.deleteForEvent(eventId);
		eventRepository.delete(event);
		log.info("Event deleted succesfully: {}", eventId);
	}
	
	@Override
	public EventResponseDto getEventById(UUID eventId) {
		Event event = eventRepository.findById(eventId)
				.filter(found -> found.getEventOrigin() == EventOrigin.VENUE)
				.orElseThrow(() -> new SoundConnectException(ErrorType.EVENT_NOT_FOUND));
		return eventMapper.toDto(event);
	}
	
	@Override
	public List<EventResponseDto> getEventsByDate(LocalDate date) {
		return eventRepository.findByEventDate(date)
				.stream().map(eventMapper::toDto).toList();
	}
	
	@Override
	public List<EventResponseDto> getEventsByCity(UUID cityId) {
		return eventRepository.findByVenue_City_Id(cityId)
				.stream().map(eventMapper::toDto).toList();
	}
	
	@Override
	public List<EventResponseDto> getEventsByDistrict(UUID districtId) {
		return eventRepository.findByVenue_District_Id(districtId)
				.stream().map(eventMapper::toDto).toList();
	}
	
	@Override
	public List<EventResponseDto> getEventsByNeighborhood(UUID neighborhoodId) {
		return eventRepository.findByVenue_Neighborhood_Id(neighborhoodId)
				.stream().map(eventMapper::toDto).toList();
	}
	
	@Override
	public List<EventResponseDto> getEventsByVenue(UUID venueId) {
		Venue venue = venueEntityFinder.getVenue(venueId);
		return eventRepository.findByVenueOrderByEventDateAscStartTimeAsc(venue)
				.stream().map(eventMapper::toDto)
				              .toList();
	}
}
