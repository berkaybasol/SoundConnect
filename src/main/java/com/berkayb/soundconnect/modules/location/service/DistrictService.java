package com.berkayb.soundconnect.modules.location.service;

import com.berkayb.soundconnect.modules.location.dto.request.DistrictRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.DistrictResponseDto;

import java.util.List;
import java.util.UUID;

public interface DistrictService {
	
	DistrictResponseDto save(DistrictRequestDto dto);
	
	List<DistrictResponseDto> findAll();
	
	List<DistrictResponseDto> findByCityId(UUID cityId);
	
	DistrictResponseDto findById(UUID id);
	
	void delete(UUID id);
}