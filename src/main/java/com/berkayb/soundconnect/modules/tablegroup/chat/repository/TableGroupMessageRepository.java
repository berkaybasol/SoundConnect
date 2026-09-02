package com.berkayb.soundconnect.modules.tablegroup.chat.repository;

import com.berkayb.soundconnect.modules.tablegroup.chat.entity.TableGroupMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.time.Instant;

public interface TableGroupMessageRepository extends JpaRepository<TableGroupMessage, UUID> {
	long countByTableGroupIdAndDeletedAtIsNull(UUID tableGroupId);

	/**
	 * Serializes one client-authored idempotency key across application nodes for
	 * the current database transaction. A 64-bit hash collision can only cause
	 * harmless extra serialization; uniqueness is still enforced by the table
	 * constraint.
	 */
	@Query(value = """
			select 1
			from pg_advisory_xact_lock(
				hashtextextended(
					cast(:tableGroupId as text) || ':' ||
					cast(:senderId as text) || ':' ||
					cast(:clientMessageId as text),
					0
				)
			)
			""", nativeQuery = true)
	int acquireClientMessageKeyLock(
			@Param("tableGroupId") UUID tableGroupId,
			@Param("senderId") UUID senderId,
			@Param("clientMessageId") UUID clientMessageId
	);

	Optional<TableGroupMessage> findByTableGroupIdAndSenderIdAndClientMessageId(
			UUID tableGroupId,
			UUID senderId,
			UUID clientMessageId
	);
	Optional<TableGroupMessage> findByGameIdAndDeletedAtIsNull(UUID gameId);
	List<TableGroupMessage> findByGameIdInAndDeletedAtIsNull(Collection<UUID> gameIds);
	
	// Page zero is the newest window. ID is the deterministic tie-breaker for
	// messages that share the same auditing timestamp.
	Page<TableGroupMessage> findByTableGroupIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
			UUID tableGroupId,
			Pageable pageable
	);

	@Query("""
			select distinct message.tableGroupId
			from TableGroupMessage message
			where exists (
				select tableGroup.id from TableGroup tableGroup
				where tableGroup.id = message.tableGroupId
				  and tableGroup.expiresAt <= :cutoff
			)
			order by message.tableGroupId
			""")
	List<UUID> findTableGroupIdsEligibleForRetention(
			@Param("cutoff") Instant cutoff,
			Pageable pageable
	);

	@Transactional
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from TableGroupMessage message where message.tableGroupId in :tableGroupIds")
	int deleteAllByTableGroupIdIn(@Param("tableGroupIds") List<UUID> tableGroupIds);
	
	@Transactional //eklendi
	@Modifying //eklendi
	@Query("delete from TableGroupMessage m where m.tableGroupId = :tableGroupId") //eklendi
	void deleteAllByTableGroupId(@Param("tableGroupId") UUID tableGroupId); //eklendi
	
}
