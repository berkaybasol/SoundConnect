package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.entity.ArtistVenueConnectionRequest;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestStatus;
import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ArtistVenueConnectionRequestRepository extends JpaRepository <ArtistVenueConnectionRequest, UUID> {
	/** Fresh account eligibility, locked against concurrent disabling until the connection commits. */
	@Query(value = """
		select id from tbl_user
		where id in :accountIds and status = 'ACTIVE' and email_verified = true
		order by id for share
		""", nativeQuery = true)
	List<UUID> lockUsableAccountIds(@Param("accountIds") Collection<UUID> accountIds);

	@Query("select request.band.id from ArtistVenueConnectionRequest request where request.id = :requestId")
	Optional<UUID> findBandIdByRequestId(@Param("requestId") UUID requestId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from ArtistVenueConnectionRequest request where request.id = :requestId")
	Optional<ArtistVenueConnectionRequest> findByIdForUpdate(@Param("requestId") UUID requestId);

	/** Serializes connection changes even when a pair has no request row yet. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select venue from Venue venue where venue.id = :venueId")
	Optional<Venue> findVenueByIdForUpdate(@Param("venueId") UUID venueId);

	@Query("""
		select new com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ConnectionVenueAvatar(
			profile.venue.id, profile.profilePictureMediaId)
		from VenueProfile profile where profile.venue.id in :venueIds
		""")
	List<ConnectionVenueAvatar> findVenueAvatars(@Param("venueIds") Collection<UUID> venueIds);

	String PAGE_SELECT = """
		select new com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.repository.ConnectionRequestRow(
			request.id, musician.id, band.id, venue.id, musician.stageName, band.name, venue.name, request.message,
			request.status, request.requestByType, request.createdAt, musician.profilePictureMediaId,
			band.profilePictureMediaId, venueProfile.profilePictureMediaId, musicianUser.username)
		from ArtistVenueConnectionRequest request
		left join request.musicianProfile musician left join musician.user musicianUser
		left join request.band band join request.venue venue
		left join VenueProfile venueProfile on venueProfile.venue.id = venue.id
		""";

	@Query(value = PAGE_SELECT + """
		where request.band.id = :bandId and (:status is null or request.status = :status)
		and request.requestByType in :requestByTypes
		""", countQuery = """
		select count(request) from ArtistVenueConnectionRequest request
		where request.band.id = :bandId and (:status is null or request.status = :status)
		and request.requestByType in :requestByTypes
		""")
	Page<ConnectionRequestRow> findBandPage(@Param("bandId") UUID bandId, @Param("status") RequestStatus status,
			@Param("requestByTypes") Collection<RequestByType> requestByTypes, Pageable pageable);

	@Query(value = PAGE_SELECT + """
		where request.musicianProfile.id = :musicianId and (:status is null or request.status = :status)
		and request.requestByType in :requestByTypes
		""", countQuery = """
		select count(request) from ArtistVenueConnectionRequest request
		where request.musicianProfile.id = :musicianId and (:status is null or request.status = :status)
		and request.requestByType in :requestByTypes
		""")
	Page<ConnectionRequestRow> findMusicianPage(@Param("musicianId") UUID musicianId, @Param("status") RequestStatus status,
			@Param("requestByTypes") Collection<RequestByType> requestByTypes, Pageable pageable);

	@Query(value = PAGE_SELECT + """
		where request.venue.id = :venueId and (:status is null or request.status = :status)
		and request.requestByType in :requestByTypes
		""", countQuery = """
		select count(request) from ArtistVenueConnectionRequest request
		where request.venue.id = :venueId and (:status is null or request.status = :status)
		and request.requestByType in :requestByTypes
		""")
	Page<ConnectionRequestRow> findVenuePage(@Param("venueId") UUID venueId, @Param("status") RequestStatus status,
			@Param("requestByTypes") Collection<RequestByType> requestByTypes, Pageable pageable);

	// muzisyenin tum basvurulari
	List<ArtistVenueConnectionRequest> findByMusicianProfileId(UUID musicianProfileId);
	
	// mekanin tum basvurulari
	List<ArtistVenueConnectionRequest> findByVenueId(UUID venueId);
	
	// ayni profil ve mekan arasinda pending var mi?
	boolean existsByMusicianProfileIdAndVenueIdAndStatus(UUID musicianProfileId, UUID venueId, RequestStatus status);
	
	boolean existsByBandIdAndVenueIdAndStatus(UUID bandId, UUID venueId, RequestStatus status);
	
	@EntityGraph(attributePaths = {"band", "musicianProfile", "venue"})
	List<ArtistVenueConnectionRequest> findAllByMusicianProfileId(UUID musicianProfileId);
	
	@EntityGraph(attributePaths = {"band", "musicianProfile", "venue"})
	List<ArtistVenueConnectionRequest> findAllByVenueId(UUID venueId);
	
	@EntityGraph(attributePaths = {"band", "musicianProfile", "venue"})
	List<ArtistVenueConnectionRequest> findAllByMusicianProfileIdAndStatus(UUID musicianProfileId, RequestStatus status);
	
	@EntityGraph(attributePaths = {"band", "musicianProfile", "venue"})
	List<ArtistVenueConnectionRequest> findAllByVenueIdAndStatus(UUID venueId, RequestStatus status);
	
	@EntityGraph(attributePaths = {"band", "musicianProfile", "venue"})
	List<ArtistVenueConnectionRequest> findAllByBandId(UUID bandId);
	
	@EntityGraph(attributePaths = {"band", "musicianProfile", "venue"})
	List<ArtistVenueConnectionRequest> findAllByBandIdAndStatus(UUID bandId, RequestStatus status);
	
	void deleteAllByBandId(UUID bandId);
	
	
}
