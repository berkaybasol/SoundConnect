package com.berkayb.soundconnect.modules.location.service;

import com.berkayb.soundconnect.modules.location.dto.request.NeighborhoodRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.NeighborhoodResponseDto;

import java.util.List;
import java.util.UUID;

public interface NeighborhoodService {
	
	NeighborhoodResponseDto save(NeighborhoodRequestDto dto);
	
	List<NeighborhoodResponseDto> findAll();
	
	NeighborhoodResponseDto findById(UUID id);
	
	List<NeighborhoodResponseDto> findByDistrictId(UUID districtId);
	
	void delete(UUID id);
}