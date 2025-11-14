package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;

import java.util.List;
import java.util.UUID;

public interface BandService {
	// band olusturur. olusturan kullanici direkt olarak founder olur.
	BandResponseDto createBand (UUID userId, BandCreateRequestDto dto);
	
	// kullanicinin uye oldugu tum bandleri listeler
	List<BandResponseDto> getBandsByUser(UUID userId);
	
	// band detayini getirir
	BandResponseDto getBandById(UUID bandId, UUID userId);
	
	// band'e uye davet et
	void inviteMember(UUID bandId, UUID inviterId, UUID invitedUserId, String message);
	
	// daveti kabul et
	void acceptInvite(UUID bandId, UUID userId);
	
	// daveti reddet
	void rejectInvite(UUID bandId, UUID userId);
	
	// Uyeyi bandden cikar
	void removeMember(UUID bandId, UUID requesterId, UUID targetUserId);
	
	// bandden ayril
	void leaveBand(UUID bandId, UUID userId);
	
	
}