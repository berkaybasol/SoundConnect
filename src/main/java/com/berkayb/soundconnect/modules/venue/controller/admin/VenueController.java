package com.berkayb.soundconnect.modules.venue.controller.admin;

import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.modules.venue.dto.request.VenueRequestDto;
import com.berkayb.soundconnect.modules.venue.dto.response.VenueResponseDto;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface VenueController {
	ResponseEntity<BaseResponse<VenueResponseDto>> save(VenueRequestDto dto);
	ResponseEntity<BaseResponse<VenueResponseDto>> update(UUID id, VenueRequestDto dto);
	ResponseEntity<BaseResponse<List<VenueResponseDto>>> findAll();
	ResponseEntity<BaseResponse<VenueResponseDto>> findById(UUID id);
	ResponseEntity<BaseResponse<Void>> delete(UUID id);
}