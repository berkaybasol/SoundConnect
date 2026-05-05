package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface ArtistVenueConnectionRequestController {
	ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> createRequest(UserDetailsImpl userDetails, ArtistVenueConnectionRequestCreateDto dto, RequestByType requestByType);
	ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> acceptRequest(UserDetailsImpl userDetails, UUID requestId);
	ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> rejectRequest(UserDetailsImpl userDetails, UUID requestId);
	ResponseEntity<BaseResponse<List<ArtistVenueConnectionRequestResponseDto>>> getRequestsByMusicianProfile(UserDetailsImpl userDetails, UUID musicianProfileId, RequestStatus status);
	ResponseEntity<BaseResponse<List<ArtistVenueConnectionRequestResponseDto>>> getRequestsByVenue(UserDetailsImpl userDetails, UUID venueId, RequestStatus status);
	ResponseEntity<BaseResponse<List<ArtistVenueConnectionRequestResponseDto>>> getRequestsByBand(UserDetailsImpl userDetails, UUID bandId, RequestStatus status);
	ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> cancelRequest(UserDetailsImpl userDetails, UUID requestId);
	ResponseEntity<BaseResponse<ArtistVenueConnectionRequestResponseDto>> disconnect(UserDetailsImpl userDetails, UUID requestId);
	
}
