package com.berkayb.soundconnect.location.controller;

import com.berkayb.soundconnect.location.dto.request.CityRequestDto;
import com.berkayb.soundconnect.location.dto.response.CityResponseDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface CityController {
	ResponseEntity<BaseResponse<CityResponseDto>> save(CityRequestDto dto);
	
	ResponseEntity<BaseResponse<List<CityResponseDto>>> getAll();
	
	ResponseEntity<BaseResponse<CityResponseDto>> getById(UUID id);
	
	ResponseEntity<BaseResponse<Void>> delete(UUID id);
}