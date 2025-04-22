package com.berkayb.soundconnect.location.controller;

import com.berkayb.soundconnect.location.dto.request.NeighborhoodRequestDto;
import com.berkayb.soundconnect.location.dto.response.NeighborhoodResponseDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface NeighborhoodController {
	
	ResponseEntity<BaseResponse<NeighborhoodResponseDto>> save(NeighborhoodRequestDto dto);
	
	ResponseEntity<BaseResponse<List<NeighborhoodResponseDto>>> getAll();
	
	ResponseEntity<BaseResponse<NeighborhoodResponseDto>> getById(UUID id);
	
	ResponseEntity<BaseResponse<List<NeighborhoodResponseDto>>> getByDistrictId(UUID districtId);
	
	ResponseEntity<BaseResponse<Void>> delete(UUID id);
}