package com.berkayb.soundconnect.modules.artistvenueconnection.repository;

import com.berkayb.soundconnect.modules.artistvenueconnection.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.artistvenueconnection.enums.RequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ArtistVenueConnectionRequestRepository extends JpaRepository <ArtistVenueConnectionRequest, UUID> {
	// muzisyenin tum basvurulari
	List<ArtistVenueConnectionRequest> findByMusicianProfileId(UUID musicianProfileId);
	
	// mekanin tum basvurulari
	List<ArtistVenueConnectionRequest> findByVenueId(UUID venueId);
	
	// ayni profil ve mekan arasinda pending var mi?
	boolean existsByMusicianProfileIdAndVenueIdAndStatus(UUID musicianProfileId, UUID venueId, RequestStatus status);
	
	List<ArtistVenueConnectionRequest> findAllByMusicianProfileId(UUID musicianProfileId);
	
	List<ArtistVenueConnectionRequest> findAllByVenueId(UUID venueId);
	
}