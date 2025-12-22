package com.berkayb.soundconnect.modules.track.repository;

import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrackRepository extends JpaRepository<Track, UUID> {
	
	// bir ownerin tum tracklerini getir
	Page<Track> findByOwnerIdAndOwnerType(UUID ownerId, TrackOwnerType ownerType, Pageable pageable);
}