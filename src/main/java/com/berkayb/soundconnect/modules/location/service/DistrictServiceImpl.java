package com.berkayb.soundconnect.modules.location.service;

import com.berkayb.soundconnect.modules.location.dto.request.DistrictRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.DistrictResponseDto;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.mapper.DistrictMapper;
import com.berkayb.soundconnect.modules.location.repository.CityRepository;
import com.berkayb.soundconnect.modules.location.repository.DistrictRepository;
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
public class DistrictServiceImpl implements DistrictService {
	private final DistrictRepository districtRepository;
	private final CityRepository cityRepository;
	private final DistrictMapper districtMapper;
	private final LocationEntityFinder locationEntityFinder;
	
	@Override
	public DistrictResponseDto save(DistrictRequestDto dto) {
		log.info("Save district: {}, for city: {}", dto.name(), dto.cityId());
		
		if (districtRepository.existsByNameAndCity_Id(dto.name(), dto.cityId())) {
			log.error("District already exists: {} in city: {}", dto.name(), dto.cityId());
			throw new SoundConnectException(ErrorType.DISTRICT_ALREADY_EXISTS);
		}
		
		City city = locationEntityFinder.getCity(dto.cityId());
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
		District district = locationEntityFinder.getDistrict(id);
		return districtMapper.toResponse(district);
	}
	
	@Override
	public void delete(UUID id) {
		log.info("Delete district by id: {}", id);
		District district = locationEntityFinder.getDistrict(id);
		districtRepository.delete(district);
	}
}