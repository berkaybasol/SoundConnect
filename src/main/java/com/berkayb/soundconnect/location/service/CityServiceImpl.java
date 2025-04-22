package com.berkayb.soundconnect.location.service;

import com.berkayb.soundconnect.location.dto.request.CityRequestDto;
import com.berkayb.soundconnect.location.dto.response.CityPrettyDto;
import com.berkayb.soundconnect.location.dto.response.CityResponseDto;
import com.berkayb.soundconnect.location.entity.City;
import com.berkayb.soundconnect.location.mapper.CityMapper;
import com.berkayb.soundconnect.location.mapper.CityPrettyMapper;
import com.berkayb.soundconnect.location.repository.CityRepository;
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
	
	
	@Override
	public CityResponseDto save(CityRequestDto dto) {
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
		City city = cityRepository.findById(id)
		                          .orElseThrow(() -> {
			                          log.error("Cannot find city by id: {}", id);
			                          return new SoundConnectException(ErrorType.CITY_NOT_FOUND);
		                          });
		return cityMapper.toResponse(city);
	}
	
	
	@Override
	public void delete(UUID id) {
	log.info("Delete city by id: {}", id);
	if (!cityRepository.existsById(id)) {
		log.error("City with id {} not found, cannot delete", id);
		throw new SoundConnectException(ErrorType.CITY_NOT_FOUND);
	}
	cityRepository.deleteById(id);
	}
	
	@Override
	public List<CityPrettyDto> findAllPretty() {
		List<City> cities = cityRepository.findAll(); // fetch = lazy ama zaten veriyi çekiyoruz
		return cities.stream().map(cityPrettyMapper::toPretty).toList();
	}
	
}