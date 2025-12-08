package com.berkayb.soundconnect.modules.track.service;

import com.berkayb.soundconnect.modules.track.dto.request.TrackCreateRequestDto;
import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface TrackService {
	
	// track olustur
	TrackResponseDto createTrack(UUID ownerId, UUID userId, TrackCreateRequestDto dto);
	
	// Track detayini getir
	TrackResponseDto getTrackById(UUID trackId);
	
	// baska moduller kullanmak isterse diye entity erisimi
	Track getTrackEntity(UUID trackId);
	
	// ownerin tum tracklerini getir
	List<TrackResponseDto> getTracksByOwner(UUID ownerId, TrackOwnerType ownerType);
	
	// track sil
	void deleteTrack(UUID trackId, UUID ownerId, UUID userId, TrackOwnerType ownerType);
	
	// list
	Page<TrackResponseDto> listTracks(UUID ownerId, TrackOwnerType ownerType, Pageable pageable);
	
}