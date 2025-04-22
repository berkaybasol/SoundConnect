package com.berkayb.soundconnect.venue.service;

import com.berkayb.soundconnect.venue.dto.request.VenueRequestDto;
import com.berkayb.soundconnect.venue.dto.response.VenueResponseDto;

import java.util.List;
import java.util.UUID;

public interface VenueService {
	
	VenueResponseDto save(VenueRequestDto dto);
	
	List<VenueResponseDto> findAll();
	
	VenueResponseDto findById(UUID id);
	
	void delete(UUID id);
	
	VenueResponseDto update(UUID id, VenueRequestDto dto);
}