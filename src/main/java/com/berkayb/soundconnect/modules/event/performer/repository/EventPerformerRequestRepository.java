package com.berkayb.soundconnect.modules.event.performer.repository;

import com.berkayb.soundconnect.modules.event.performer.entity.EventPerformerRequest;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestAuthorizationSnapshot;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestLockTarget;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface EventPerformerRequestRepository extends JpaRepository<EventPerformerRequest, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from EventPerformerRequest request where request.id = :requestId")
	Optional<EventPerformerRequest> findByIdForUpdate(@Param("requestId") UUID requestId);

	@Query("""
			select new com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestAuthorizationSnapshot(
				request.event.id,
				musicianOwner.id,
				band.id
			)
			from EventPerformerRequest request
			left join request.musicianProfile musician
			left join musician.user musicianOwner
			left join request.band band
			where request.id = :requestId
			""")
	Optional<EventPerformerRequestAuthorizationSnapshot> findAuthorizationSnapshotById(
			@Param("requestId") UUID requestId
	);

	@EntityGraph(attributePaths = {"event", "event.venue", "musicianProfile", "musicianProfile.user", "band"})
	@Query(value = """
			select distinct request
			from EventPerformerRequest request
			left join request.musicianProfile musician
			left join musician.user musicianOwner
			left join request.band band
			left join band.members member
			where (musicianOwner.id = :userId
			   or (member.user.id = :userId
			       and member.status = :membershipStatus
			       and member.bandRole = :bandRole))
			order by request.createdAt desc, request.id desc
			""",
			countQuery = """
			select count(distinct request.id)
			from EventPerformerRequest request
			left join request.musicianProfile musician
			left join musician.user musicianOwner
			left join request.band band
			left join band.members member
			where (musicianOwner.id = :userId
			   or (member.user.id = :userId
			       and member.status = :membershipStatus
			       and member.bandRole = :bandRole))
			""")
	Page<EventPerformerRequest> findMine(
			@Param("userId") UUID userId,
			@Param("membershipStatus") BandMemberShipStatus membershipStatus,
			@Param("bandRole") BandRole bandRole,
			Pageable pageable
	);

	@EntityGraph(attributePaths = {"event", "event.venue", "musicianProfile", "musicianProfile.user", "band"})
	@Query(value = """
			select distinct request
			from EventPerformerRequest request
			left join request.musicianProfile musician
			left join musician.user musicianOwner
			left join request.band band
			left join band.members member
			where request.status = :status
			  and (musicianOwner.id = :userId
			   or (member.user.id = :userId
			       and member.status = :membershipStatus
			       and member.bandRole = :bandRole))
			order by request.createdAt desc, request.id desc
			""",
			countQuery = """
			select count(distinct request.id)
			from EventPerformerRequest request
			left join request.musicianProfile musician
			left join musician.user musicianOwner
			left join request.band band
			left join band.members member
			where request.status = :status
			  and (musicianOwner.id = :userId
			   or (member.user.id = :userId
			       and member.status = :membershipStatus
			       and member.bandRole = :bandRole))
			""")
	Page<EventPerformerRequest> findMineByStatus(
			@Param("userId") UUID userId,
			@Param("status") EventPerformerRequestStatus status,
			@Param("membershipStatus") BandMemberShipStatus membershipStatus,
			@Param("bandRole") BandRole bandRole,
			Pageable pageable
	);

	@EntityGraph(attributePaths = {"event", "event.venue", "musicianProfile", "musicianProfile.user", "band"})
	@Query(value = """
			select request
			from EventPerformerRequest request
			join request.musicianProfile musician
			where musician.id = :targetId
			  and musician.user.id = :userId
			  and (:status is null or request.status = :status)
			order by request.createdAt desc, request.id desc
			""",
			countQuery = """
			select count(request.id)
			from EventPerformerRequest request
			join request.musicianProfile musician
			where musician.id = :targetId
			  and musician.user.id = :userId
			  and (:status is null or request.status = :status)
			""")
	Page<EventPerformerRequest> findMineForMusician(
			@Param("userId") UUID userId,
			@Param("targetId") UUID targetId,
			@Param("status") EventPerformerRequestStatus status,
			Pageable pageable
	);

	@EntityGraph(attributePaths = {"event", "event.venue", "musicianProfile", "musicianProfile.user", "band"})
	@Query(value = """
			select distinct request
			from EventPerformerRequest request
			join request.band band
			join band.members member
			where band.id = :targetId
			  and member.user.id = :userId
			  and member.status = :membershipStatus
			  and member.bandRole = :bandRole
			  and (:status is null or request.status = :status)
			order by request.createdAt desc, request.id desc
			""",
			countQuery = """
			select count(distinct request.id)
			from EventPerformerRequest request
			join request.band band
			join band.members member
			where band.id = :targetId
			  and member.user.id = :userId
			  and member.status = :membershipStatus
			  and member.bandRole = :bandRole
			  and (:status is null or request.status = :status)
			""")
	Page<EventPerformerRequest> findMineForBand(
			@Param("userId") UUID userId,
			@Param("targetId") UUID targetId,
			@Param("status") EventPerformerRequestStatus status,
			@Param("membershipStatus") BandMemberShipStatus membershipStatus,
			@Param("bandRole") BandRole bandRole,
			Pageable pageable
	);

	void deleteAllByEvent_Id(UUID eventId);

	@Query("""
			select new com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestLockTarget(
				request.id,
				request.event.id
			)
			from EventPerformerRequest request
			where request.band.id = :bandId
			order by request.event.id, request.id
			""")
	List<EventPerformerRequestLockTarget> findLockTargetsByBandId(@Param("bandId") UUID bandId);

	@EntityGraph(attributePaths = "event")
	List<EventPerformerRequest> findAllByMusicianProfile_Id(UUID musicianProfileId);
}
