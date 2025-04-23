package com.berkayb.soundconnect.modules.location.service;

import com.berkayb.soundconnect.modules.location.dto.request.CityRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.CityPrettyDto;
import com.berkayb.soundconnect.modules.location.dto.response.CityResponseDto;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.mapper.CityMapper;
import com.berkayb.soundconnect.modules.location.mapper.CityPrettyMapper;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CityServiceImpl implements CityService {
	private final CityRepository cityRepository;
	private final CityMapper cityMapper;
	private final CityPrettyMapper cityPrettyMapper;
	private final LocationEntityFinder locationEntityFinder;
	
	
	@Override
	public CityResponseDto save(CityRequestDto dto) { // locationfinder kullanamiyoz cunku db de yok zaten yeni ekleniyo :D
		if (cityRepository.existsByName(dto.name())){
			log.error("City already exists: {}", dto.name());
			throw new SoundConnectException(ErrorType.CITY_ALREADY_EXISTS);
		}
		log.info("Save city: {}", dto.name());
		City city = cityMapper.toEntity(dto);
		City savedCity = cityRepository.save(city);
		return cityMapper.toResponse(savedCity);
	}
	
	@Override
	public List<CityResponseDto> findAll() {
		log.info("Find all cities");
		List<City> cities = cityRepository.findAll();
		return cityMapper.toResponseList(cities);
	}
	
	@Override
	public CityResponseDto findById(UUID id) {
		log.info("Find city by id: {}", id);
		City city = locationEntityFinder.getCity(id);
		return cityMapper.toResponse(city);
	}
	
	
	@Override
	public void delete(UUID id) {
	log.info("Delete city by id: {}", id);
		City city = locationEntityFinder.getCity(id);
		cityRepository.delete(city);
	}
	
	@Override
	public List<CityPrettyDto> findAllPretty() {
		List<City> cities = cityRepository.findAll(); // fetch = lazy ama zaten veriyi çekiyoruz
		return cities.stream().map(cityPrettyMapper::toPretty).toList();
	}
	
}