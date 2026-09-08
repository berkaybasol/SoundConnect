package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandMemberTitleUpdateDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandMemberResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandPendingInvitationResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandReceivedInvitationResponseDto;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandSearchItemDto; //eklendi
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;

import java.util.List;
import java.util.UUID;

public interface BandService {
	// band olusturur. olusturan kullanici direkt olarak founder olur.
	BandResponseDto createBand(UUID userId, BandCreateRequestDto dto);
	
	// band profilini gunceller
	BandResponseDto updateBand(UUID bandId, UUID userId, BandCreateRequestDto dto);
	
	// kullanicinin uye oldugu tum bandleri listeler
	List<BandResponseDto> getBandsByUser(UUID userId);
	
	// band detayini getirir
	BandResponseDto getBandById(UUID bandId, UUID userId);
	
	//eklendi
	// public band detayini getirir
	BandResponseDto getPublicBandById(UUID bandId);
	
	//eklendi
	// band search
	List<BandSearchItemDto> searchBands(String query);
	
	// band'e uye davet et
	void inviteMember(UUID bandId, UUID inviterId, UUID invitedUserId, String message);

	PageResponse<BandPendingInvitationResponseDto> getPendingInvitations(
			UUID bandId, UUID requesterId, int page, int size);

	PageResponse<BandReceivedInvitationResponseDto> getReceivedInvitations(UUID requesterId, int page, int size);
	BandReceivedInvitationResponseDto getCurrentReceivedInvitation(UUID bandId, UUID requesterId);
	
	// daveti kabul et
	void acceptInvite(UUID bandId, UUID userId, UUID invitationId);
	
	// daveti reddet
	void rejectInvite(UUID bandId, UUID userId, UUID invitationId);
	
	// Uyeyi bandden cikar
	void removeMember(UUID bandId, UUID requesterId, UUID targetUserId, Long expectedTitleVersion);
	
	// bandden ayril
	void leaveBand(UUID bandId, UUID userId, Long expectedTitleVersion);

	BandMemberResponseDto updateMemberTitle(UUID bandId, UUID requesterId, UUID targetUserId,
	                                      BandMemberTitleUpdateDto update);

	// bandi sil
	void deleteBand(UUID bandId, UUID userId);
	
	Band getBandEntity(UUID bandId);
}
