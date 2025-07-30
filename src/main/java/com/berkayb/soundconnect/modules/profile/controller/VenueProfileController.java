package com.berkayb.soundconnect.modules.profile.controller;

import com.berkayb.soundconnect.modules.profile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.dto.response.VenueProfileResponseDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

public interface VenueProfileController {
	ResponseEntity<BaseResponse<VenueProfileResponseDto>> createProfile(UUID venueId, VenueProfileSaveRequestDto dto);
	ResponseEntity<BaseResponse<VenueProfileResponseDto>> getProfile(UUID venueId);
	ResponseEntity<BaseResponse<VenueProfileResponseDto>> updateProfile(UUID venueId, VenueProfileSaveRequestDto dto);
}