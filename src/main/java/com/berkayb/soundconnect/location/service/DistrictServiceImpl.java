package com.berkayb.soundconnect.location.service;

import com.berkayb.soundconnect.location.dto.request.DistrictRequestDto;
import com.berkayb.soundconnect.location.dto.response.DistrictResponseDto;
import com.berkayb.soundconnect.location.entity.City;
import com.berkayb.soundconnect.location.entity.District;
import com.berkayb.soundconnect.location.mapper.DistrictMapper;
import com.berkayb.soundconnect.location.repository.CityRepository;
import com.berkayb.soundconnect.location.repository.DistrictRepository;
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
public class DistrictServiceImpl implements DistrictService {
	private final DistrictRepository districtRepository;
	private final CityRepository cityRepository;
	private final DistrictMapper districtMapper;
	
	@Override
	public DistrictResponseDto save(DistrictRequestDto dto) {
		log.info("Save district: {}, for ciy: {}", dto.name(), dto.cityId());
		
		if (districtRepository.existsByNameAndCity_Id(dto.name(), dto.cityId())) {
			log.error("district already exists: {} in city: {}", dto.name(), dto.cityId());
			throw new SoundConnectException(ErrorType.DISTRICT_ALREADY_EXISTS);
		}
		
		City city = cityRepository.findById(dto.cityId()).orElseThrow(() -> {
			log.error("city not found with id: {}", dto.cityId());
			return new SoundConnectException(ErrorType.CITY_NOT_FOUND);
		});
		
		District district = districtMapper.toEntity(dto);
		district.setCity(city); // entityi burda bagliyoruz
		
		District saved = districtRepository.save(district);
		return districtMapper.toResponse(saved);
	}
	
	@Override
	public List<DistrictResponseDto> findAll() {
		log.info("get all districts");
		return districtMapper.toResponseList(districtRepository.findAll());
	}
	
	@Override
	public List<DistrictResponseDto> findByCityId(UUID cityId) {
		log.info("find district by id: {}", cityId);
		return districtMapper.toResponseList(districtRepository.findDistrictsByCity_Id(cityId));
	}
	
	@Override
	public DistrictResponseDto findById(UUID id) {
		log.info("Find district by id: {}", id);
		District district = districtRepository.findById(id)
		                                      .orElseThrow(() -> {
			                                      log.error("District not found by id: {}", id);
			                                      return new SoundConnectException(ErrorType.DISTRICT_NOT_FOUND);
		                                      });
		return districtMapper.toResponse(district);
	}
	
	@Override
	public void delete(UUID id) {
		log.info("Delete district by id: {}", id);
		if (!districtRepository.existsById(id)) {
			log.error("District not found for deletion: {}", id);
			throw new SoundConnectException(ErrorType.DISTRICT_NOT_FOUND);
		}
		districtRepository.deleteById(id);
	}
}