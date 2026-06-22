package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.request.ArtistVenueConnectionRequestCreateDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response.ArtistVenueConnectionRequestResponseDto;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;

import java.util.List;
import java.util.UUID;

public interface ArtistVenueConnectionRequestService {
	// basvuru olustur (sanatci veya mekan baslatabilir)
	ArtistVenueConnectionRequestResponseDto createRequest(UUID actorUserId, ArtistVenueConnectionRequestCreateDto dto, RequestByType requestType);
	
	// basvuruyu onayla
	ArtistVenueConnectionRequestResponseDto acceptRequest(UUID actorUserId, UUID requestId);
	
	// basvuruyu reddet
	ArtistVenueConnectionRequestResponseDto rejectRequest(UUID actorUserId, UUID requestId);
	
	// muzisyenin yaptigi basvurulari getir.
	List<ArtistVenueConnectionRequestResponseDto> getRequestByMusicianProfile(UUID actorUserId, UUID musicianProfileId, RequestStatus status);
	
	// mekanin aldigi basvurulari getir
	List<ArtistVenueConnectionRequestResponseDto> getRequestsByVenue(UUID actorUserId, UUID venueId, RequestStatus status);
	
	// istegini iptal et
	ArtistVenueConnectionRequestResponseDto cancelRequest(UUID actorUserId, UUID requestId);
	
	ArtistVenueConnectionRequestResponseDto disconnect(UUID actorUserId, UUID requestId);
	
	List<ArtistVenueConnectionRequestResponseDto> getRequestsByBand(UUID actorUserId, UUID bandId, RequestStatus status);
	
	
}
