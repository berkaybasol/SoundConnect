package com.berkayb.soundconnect.location.support;

import com.berkayb.soundconnect.location.entity.City;
import com.berkayb.soundconnect.location.entity.District;
import com.berkayb.soundconnect.location.entity.Neighborhood;
import com.berkayb.soundconnect.location.repository.CityRepository;
import com.berkayb.soundconnect.location.repository.DistrictRepository;
import com.berkayb.soundconnect.location.repository.NeighborhoodRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LocationEntityFinder {
	private final CityRepository cityRepository;
	private final DistrictRepository districtRepository;
	private final NeighborhoodRepository neighborhoodRepository;
	
	public City getCity(UUID id) {
		return cityRepository.findById(id)
		                     .orElseThrow(() -> new SoundConnectException(ErrorType.CITY_NOT_FOUND));
	}
	
	public District getDistrict(UUID id) {
		return districtRepository.findById(id)
		                         .orElseThrow(() -> new SoundConnectException(ErrorType.DISTRICT_NOT_FOUND));
	}
	
	public Neighborhood getNeighborhood(UUID id) {
		return neighborhoodRepository.findById(id)
		                             .orElseThrow(() -> new SoundConnectException(ErrorType.NEIGHBORHOOD_NOT_FOUND));
	}
}