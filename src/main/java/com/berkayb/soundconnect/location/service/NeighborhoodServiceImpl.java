package com.berkayb.soundconnect.location.service;


import com.berkayb.soundconnect.location.dto.request.NeighborhoodRequestDto;
import com.berkayb.soundconnect.location.dto.response.NeighborhoodResponseDto;
import com.berkayb.soundconnect.location.entity.District;
import com.berkayb.soundconnect.location.entity.Neighborhood;
import com.berkayb.soundconnect.location.mapper.NeighborhoodMapper;
import com.berkayb.soundconnect.location.repository.DistrictRepository;
import com.berkayb.soundconnect.location.repository.NeighborhoodRepository;
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
public class NeighborhoodServiceImpl implements NeighborhoodService {
	
	private final NeighborhoodRepository neighborhoodRepository;
	private final DistrictRepository districtRepository;
	private final NeighborhoodMapper neighborhoodMapper;
	
	
	@Override
	public NeighborhoodResponseDto save(NeighborhoodRequestDto dto) {
		log.info("save neighborhood request: {}, for district: {} ", dto.name(), dto.districtId());
		
		if (neighborhoodRepository.existsByNameAndDistrict_Id(dto.name(), dto.districtId()))
		{
			log.error("neighborhood already exists: {}, in district: {}", dto.name(), dto.districtId());
			throw new SoundConnectException(ErrorType.NEIGHBORHOOD_ALREADY_EXISTS);
		}
		
		District district = districtRepository.findById(dto.districtId())
		                                      .orElseThrow(() -> {
			                                      log.error("District not found with id: {}", dto.districtId());
			                                      return new SoundConnectException(ErrorType.DISTRICT_NOT_FOUND);
		                                      });
		
		Neighborhood neighborhood = neighborhoodMapper.toEntity(dto);
		neighborhood.setDistrict(district);
		
		Neighborhood saved = neighborhoodRepository.save(neighborhood);
		return neighborhoodMapper.toResponse(saved);
	}
	
	@Override
	public List<NeighborhoodResponseDto> findAll() {
		log.info("Get all neighborhoods");
		return neighborhoodMapper.toResponseList(neighborhoodRepository.findAll());
	}
	
	@Override
	public NeighborhoodResponseDto findById(UUID id) {
		log.info("Find neighborhood by id: {}", id);
		Neighborhood neighborhood = neighborhoodRepository.findById(id)
		                                                  .orElseThrow(() -> {
			                                                  log.error("Neighborhood not found by id: {}", id);
			                                                  return new SoundConnectException(ErrorType.NEIGHBORHOOD_NOT_FOUND);
		                                                  });
		return neighborhoodMapper.toResponse(neighborhood);
		
	}
	
	@Override
	public List<NeighborhoodResponseDto> findByDistrictId(UUID districtId) {
		log.info("Find neighborhoods by districtId: {}", districtId);
		return neighborhoodMapper.toResponseList(neighborhoodRepository.findAllByDistrict_Id(districtId));
	}
	
	@Override
	public void delete(UUID id) {
		log.info("Delete neighborhood by id: {}", id);
		if (!neighborhoodRepository.existsById(id)) {
			log.error("Neighborhood not found for deletion: {}", id);
			throw new SoundConnectException(ErrorType.NEIGHBORHOOD_NOT_FOUND);
		}
		neighborhoodRepository.deleteById(id);
	}
}