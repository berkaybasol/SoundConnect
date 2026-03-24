package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;

import java.util.List;
import java.util.UUID;

public interface ArtistVenueConnectionRequestService {
	// basvuru olustur (sanatci veya mekan baslatabilir)
	ArtistVenueConnectionRequestResponseDto createRequest(ArtistVenueConnectionRequestCreateDto dto, RequestByType requestType);
	
	// basvuruyu onayla
	ArtistVenueConnectionRequestResponseDto acceptRequest(UUID requestId);
	
	// basvuruyu reddet
	ArtistVenueConnectionRequestResponseDto rejectRequest(UUID requestId);
	
	// muzisyenin yaptigi basvurulari getir.
	List<ArtistVenueConnectionRequestResponseDto> getRequestByMusicianProfile(UUID musicianProfileId, RequestStatus status);
	
	// mekanin aldigi basvurulari getir
	List<ArtistVenueConnectionRequestResponseDto> getRequestsByVenue(UUID venueId, RequestStatus status);
	
	// istegini iptal et
	ArtistVenueConnectionRequestResponseDto cancelRequest(UUID requestId);
	
	ArtistVenueConnectionRequestResponseDto disconnect(UUID requestId);
	
}