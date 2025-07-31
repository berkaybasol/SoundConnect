package com.berkayb.soundconnect.modules.artistvenueconnection.controller;

import com.berkayb.soundconnect.modules.artistvenueconnection.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.artistvenueconnection.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.artistvenueconnection.enums.RequestByType;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface ArtistVenueConnectionRequestController {
	ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> createRequest(ArtistVenueConnectionRequestCreateDto dto, RequestByType requestByType);
	ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> acceptRequest(UUID requestId);
	ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> rejectRequest(UUID requestId);
	ResponseEntity<BaseResponse<List<ArtistVenueConnectionRequestResponseDto>>> getRequestsByMusicianProfile(UUID musicianProfileId);
	ResponseEntity<BaseResponse<List<ArtistVenueConnectionRequestResponseDto>>> getRequestsByVenue(UUID venueId);
}