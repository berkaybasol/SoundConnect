package com.berkayb.soundconnect.modules.location.controller;

import com.berkayb.soundconnect.modules.location.dto.request.DistrictRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.DistrictResponseDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface DistrictController {
	ResponseEntity<BaseResponse<DistrictResponseDto>> save(DistrictRequestDto dto);
	
	ResponseEntity<BaseResponse<List<DistrictResponseDto>>> getAll();
	
	ResponseEntity<BaseResponse<DistrictResponseDto>> getById(UUID id);
	
	ResponseEntity<BaseResponse<List<DistrictResponseDto>>> getByCityId(UUID cityId);
	
	ResponseEntity<BaseResponse<Void>> delete(UUID id);
}