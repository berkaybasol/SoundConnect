package com.berkayb.soundconnect.modules.tablegroup.repository;

import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface TableGroupRepository extends JpaRepository<TableGroup, UUID> {

	/**
	 * Serializes all lifecycle mutations for one table. The table row is the
	 * aggregate lock; its element collection is then read and changed while the
	 * transaction still owns this lock.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select tableGroup from TableGroup tableGroup where tableGroup.id = :id")
	Optional<TableGroup> findByIdForUpdate(@Param("id") UUID id);

	Optional<TableGroup> findByOwnerIdAndCreateRequestKey(UUID ownerId, UUID createRequestKey);

	@Query("select tableGroup.ownerId from TableGroup tableGroup where tableGroup.id = :id")
	Optional<UUID> findOwnerIdById(@Param("id") UUID id);

	@Query("select tableGroup.status from TableGroup tableGroup where tableGroup.id = :id")
	Optional<TableGroupStatus> findStatusById(@Param("id") UUID id);

	/**
	 * Cheap authorization/shape preflight for an owner decision. It prevents a
	 * caller from using arbitrary user ids to contend on the shared user-row
	 * lifecycle mutex. The participant is re-read under the table lock before a
	 * decision is committed.
	 */
	@Query("""
			select participant.status
			from TableGroup tableGroup
			join tableGroup.participants participant
			where tableGroup.id = :tableGroupId
			  and tableGroup.ownerId = :ownerId
			  and participant.userId = :participantId
			""")
	Optional<ParticipantStatus> findParticipantStatusForOwner(
			@Param("tableGroupId") UUID tableGroupId,
			@Param("ownerId") UUID ownerId,
			@Param("participantId") UUID participantId
	);

	/**
	 * Cheap participant-shape preflight for self-service lifecycle operations.
	 * The aggregate and participant are always re-read under the table lock before
	 * a mutation or idempotent success is committed.
	 */
	@Query("""
			select participant.status
			from TableGroup tableGroup
			join tableGroup.participants participant
			where tableGroup.id = :tableGroupId
			  and participant.userId = :participantId
			""")
	Optional<ParticipantStatus> findParticipantStatus(
			@Param("tableGroupId") UUID tableGroupId,
			@Param("participantId") UUID participantId
	);

	/**
	 * Snapshot of the owner's logically open tables. Callers that mutate lifecycle
	 * state lock the returned aggregate ids in a deterministic order afterwards.
	 * The expiry predicate deliberately ignores scheduler lag: an elapsed table is
	 * already closed from the user's perspective even if its persisted status has
	 * not been normalized to {@code INACTIVE} yet.
	 */
	@Query("""
			select tableGroup.id
			from TableGroup tableGroup
			where tableGroup.ownerId = :ownerId
			  and tableGroup.status = :status
			  and tableGroup.expiresAt > :now
			""")
	List<UUID> findOpenIdsByOwner(
			@Param("ownerId") UUID ownerId,
			@Param("status") TableGroupStatus status,
			@Param("now") Instant now
	);

	@Query("""
			select count(distinct tableGroup)
			from TableGroup tableGroup
			left join tableGroup.participants participant
			where tableGroup.id = :tableGroupId
			  and tableGroup.status = :status
			  and tableGroup.expiresAt > :now
			  and (tableGroup.ownerId = :userId
			       or (participant.userId = :userId and participant.status = :participantStatus))
			""")
	long countOpenAccess(
			@Param("tableGroupId") UUID tableGroupId,
			@Param("userId") UUID userId,
			@Param("status") TableGroupStatus status,
			@Param("participantStatus") com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus participantStatus,
			@Param("now") Instant now
	);

	@EntityGraph(attributePaths = {"city", "district", "neighborhood"})
	Page<TableGroup> findByCityIdAndDistrictIdAndNeighborhoodIdAndStatusAndExpiresAtAfter(UUID cityId,
	                                                                                      UUID districtId,
	                                                                                      UUID neighborhoodId,
	                                                                                      TableGroupStatus status,
	                                                                                      Instant expiresAt,
	                                                                                      Pageable pageable);
	
	@EntityGraph(attributePaths = {"city", "district", "neighborhood"})
	Page<TableGroup> findByCityIdAndDistrictIdAndStatusAndExpiresAtAfter(UUID cityId, UUID districtId, TableGroupStatus status, Instant expiresAt, Pageable pageable);
	
	@EntityGraph(attributePaths = {"city", "district", "neighborhood"})
	Page<TableGroup> findByCityIdAndStatusAndExpiresAtAfter(UUID cityId, TableGroupStatus status,
	                                                        Instant expiresAt, Pageable pageable);

	@EntityGraph(attributePaths = {"city", "district", "neighborhood"})
	Page<TableGroup> findByStatusAndExpiresAtAfter(
			TableGroupStatus status,
			Instant expiresAt,
			Pageable pageable
	);

	@EntityGraph(attributePaths = {"city", "district", "neighborhood"})
	@Query(value = """
			select tableGroup
			from TableGroup tableGroup
			where tableGroup.status = :status
			  and tableGroup.expiresAt > :now
			  and (tableGroup.ownerId = :userId
			       or tableGroup.id in (
			           select participatingTable.id
			           from TableGroup participatingTable
			           join participatingTable.participants participant
			           where participant.userId = :userId
			             and participant.status = :participantStatus
			       ))
			""", countQuery = """
			select count(tableGroup.id)
			from TableGroup tableGroup
			where tableGroup.status = :status
			  and tableGroup.expiresAt > :now
			  and (tableGroup.ownerId = :userId
			       or tableGroup.id in (
			           select participatingTable.id
			           from TableGroup participatingTable
			           join participatingTable.participants participant
			           where participant.userId = :userId
			             and participant.status = :participantStatus
			       ))
			""")
	Page<TableGroup> findActiveAccessibleByUser(
			@Param("userId") UUID userId,
			@Param("status") TableGroupStatus status,
			@Param("participantStatus") ParticipantStatus participantStatus,
			@Param("now") Instant now,
			Pageable pageable
	);
	
	@Query("""
			select tableGroup.id
			from TableGroup tableGroup
			where tableGroup.status = :status and tableGroup.expiresAt <= :cutOffTime
			order by tableGroup.expiresAt, tableGroup.id
			""")
	List<UUID> findExpiredIds(
			@Param("status") TableGroupStatus status,
			@Param("cutOffTime") Instant cutOffTime,
			Pageable pageable
	);

	@Query("""
			select distinct tableGroup.id
			from TableGroup tableGroup
			join tableGroup.participants participant
			where participant.status in :statuses
			  and participant.joinedAt < :cutoff
			order by tableGroup.id
			""")
	List<UUID> findIdsWithTerminalParticipantsBefore(
			@Param("statuses") Set<ParticipantStatus> statuses,
			@Param("cutoff") Instant cutoff,
			Pageable pageable
	);
	
	
}
