package com.berkayb.soundconnect.modules.event.service;

import com.berkayb.soundconnect.modules.event.dto.request.EventCreateRequestDto;
import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface EventService {
	// ============ ADMIN ==============
	
	// event olustur
	EventResponseDto createEvent(UUID createdByUserId, EventCreateRequestDto dto);
	
	// id gore event getir
	EventResponseDto getEventById(UUID eventId);
	
	// id gore event sil
	void deleteEventById(UUID eventId);
	
	// ============ USER ==============
	
	// tarihe gore event getir
	List<EventResponseDto> getEventsByDate(LocalDate date);
	
	// sehre gore event getir
	List<EventResponseDto> getEventsByCity(UUID cityId);
	
	// ilceye gore event getir
	List<EventResponseDto> getEventsByDistrict(UUID districtId);
	
	// mahalleye gore event getir
	List<EventResponseDto> getEventsByNeighborhood(UUID neighborhoodId);
	
	// mekana gore event getir
	List<EventResponseDto> getEventsByVenue(UUID venueId);
}