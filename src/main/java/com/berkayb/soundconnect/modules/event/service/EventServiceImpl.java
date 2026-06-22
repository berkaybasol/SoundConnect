package com.berkayb.soundconnect.modules.event.service;

import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.entity.Event;
import com.berkayb.soundconnect.modules.event.mapper.EventMapper;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.service.MusicianProfileService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.support.VenueEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
	private final BandService bandService;
	private final EventMapper eventMapper;
	
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
		
		// hangisi geldiyse onu getir
		if (musicianProvided) { //degisti
			musician = musicianProfileService.getProfileEntity(dto.musicianProfileId());
		} else if (bandProvided) { //degisti
			band = bandService.getBandEntity(dto.bandId());
		}
		
		// event olustur
		Event event = Event.builder()
				.title(dto.title())
				.description(dto.description())
				.eventDate(dto.eventDate())
				.startTime(dto.startTime())
				.endTime(dto.endTime())
				.posterImage(dto.posterImage())
				.venue(venue)
				.musicianProfile(musician)
				.band(band)
				.manualPerformerName(manualProvided ? dto.manualPerformerName().trim() : null)
				.build();
		
		Event saved = eventRepository.save(event);
		
		log.info("Event created succesfully: {}", saved.getId());
		
		return eventMapper.toDto(saved);
		
	}
	
	@Override
	public void deleteEventById(UUID deletedByUserId, UUID eventId) {
		// degistirildi: event'i silen kullaniciyi dogrula
		User deletedByUser = userEntityFinder.getUser(deletedByUserId);
		
		Event event = eventRepository.findById(eventId)
		                             .orElseThrow(() -> new SoundConnectException(ErrorType.EVENT_NOT_FOUND));
		
		// degistirildi: sadece event'in bagli oldugu venue'nun sahibi silebilsin
		if (event.getVenue() == null || event.getVenue().getOwner() == null ||
				!event.getVenue().getOwner().getId().equals(deletedByUser.getId())) {
			log.warn("[EVENT] Kullanici bu event'i silme yetkisine sahip degil. userId={}, eventId={}",
			         deletedByUserId, eventId);
			throw new SoundConnectException(ErrorType.EVENT_NOT_FOUND);
		}
		
		eventRepository.delete(event);
		log.info("Event deleted succesfully: {}", eventId);
	}
	
	@Override
	public EventResponseDto getEventById(UUID eventId) {
		Event event = eventRepository.findById(eventId)
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