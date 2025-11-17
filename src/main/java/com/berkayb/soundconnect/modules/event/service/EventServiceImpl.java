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
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
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
	public EventResponseDto createEvent(UUID createdByUserId, EventCreateRequestDto dto) {
		log.info("[EVENT] Yeni etkinlik oluşturma isteği alındı. title={}", dto.title());
		
		// kullanici var mi?
		userEntityFinder.getUser(createdByUserId);
		
		// Venue dogru mu?
		Venue venue = venueEntityFinder.getVenue(dto.venueId());
		
		// Performer dogrulamasi
		boolean musicianProvided = dto.musicianProfileId() != null;
		boolean bandProvided = dto.bandId() != null;
		
		if (musicianProvided == bandProvided) {
			// ya ikisi de null → hata
			// ya ikisi de dolu → hata
			log.warn("[EVENT] Performer doğrulaması başarısız. musician={}, band={}",
			         dto.musicianProfileId(), dto.bandId());
			throw new SoundConnectException(ErrorType.INVALID_PERFORMER_SELECTION);
		}
		
		MusicianProfile musician = null;
		Band band = null;
		
		// hangisi geldiyse onu getir
		if (musicianProvided) {
			musician = musicianProfileService.getProfileEntity(dto.musicianProfileId());
		} else {
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
				           .build();
		
		Event saved = eventRepository.save(event);
		
		log.info("Event created succesfully: {}", saved.getId());
		
		return eventMapper.toDto(saved);
		
	}
	
	@Override
	public void deleteEventById(UUID eventId) {
		Event event = eventRepository.findById(eventId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.EVENT_NOT_FOUND));
		
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
		return eventRepository.findByVenue(venue)
				.stream().map(eventMapper::toDto).toList();
	}
}