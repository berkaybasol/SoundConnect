package com.berkayb.soundconnect.venue.service;

import com.berkayb.soundconnect.location.repository.CityRepository;
import com.berkayb.soundconnect.location.repository.DistrictRepository;
import com.berkayb.soundconnect.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.user.repository.UserRepository;
import com.berkayb.soundconnect.venue.dto.request.VenueRequestDto;
import com.berkayb.soundconnect.venue.dto.response.VenueResponseDto;
import com.berkayb.soundconnect.venue.mapper.VenueMapper;
import com.berkayb.soundconnect.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VenueServiceImpl implements VenueService {
	private final VenueRepository venueRepository;
	private final VenueMapper venueMapper;
	private final CityRepository cityRepository;
	private final DistrictRepository districtRepository;
	private final NeighborhoodRepository neighborhoodRepository;
	private final UserRepository userRepository;
	
	@Override
	public VenueResponseDto save(VenueRequestDto dto) {
		log.info("Saving venue: {}", dto.name());
		
		City
	}
	
	@Override
	public List<VenueResponseDto> findAll() {
		return List.of();
	}
	
	@Override
	public VenueResponseDto findById(UUID id) {
		return null;
	}
	
	@Override
	public void delete(UUID id) {
	
	}
	
	@Override
	public VenueResponseDto update(UUID id, VenueRequestDto dto) {
		return null;
	}
}