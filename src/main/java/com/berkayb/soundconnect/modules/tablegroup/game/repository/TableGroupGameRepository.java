package com.berkayb.soundconnect.modules.tablegroup.game.repository;

import com.berkayb.soundconnect.modules.tablegroup.game.entity.TableGroupGame;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

public interface TableGroupGameRepository extends JpaRepository<TableGroupGame, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select game from TableGroupGame game where game.id = :gameId")
	Optional<TableGroupGame> findByIdForUpdate(@Param("gameId") UUID gameId);

	@Query("select game.tableGroupId from TableGroupGame game where game.id = :gameId")
	Optional<UUID> findTableGroupIdById(@Param("gameId") UUID gameId);

	Optional<TableGroupGame> findByTableGroupIdAndCreatedByAndCreateRequestId(
			UUID tableGroupId,
			UUID createdBy,
			UUID createRequestId
	);

	Optional<TableGroupGame> findFirstByTableGroupIdAndStatusInOrderByCreatedAtDesc(
			UUID tableGroupId,
			Collection<TableGroupGameStatus> statuses
	);

	@Query("""
			select game.id from TableGroupGame game
			where (game.status = :lobby and game.joinDeadlineAt <= :now)
			   or (game.status = :inProgress and game.actionDeadlineAt <= :now)
			order by coalesce(game.actionDeadlineAt, game.joinDeadlineAt), game.id
			""")
	List<UUID> findDueIds(
			@Param("lobby") TableGroupGameStatus lobby,
			@Param("inProgress") TableGroupGameStatus inProgress,
			@Param("now") Instant now,
			Pageable pageable
	);

	List<TableGroupGame> findByIdIn(Collection<UUID> ids);

	@Query("select game.id from TableGroupGame game where game.tableGroupId in :tableGroupIds")
	List<UUID> findIdsByTableGroupIdIn(@Param("tableGroupIds") Collection<UUID> tableGroupIds);

	@Query("select game.id from TableGroupGame game where game.tableGroupId = :tableGroupId")
	List<UUID> findIdsByTableGroupId(@Param("tableGroupId") UUID tableGroupId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from TableGroupGame game where game.id in :gameIds")
	int deleteAllByIdInBulk(@Param("gameIds") Collection<UUID> gameIds);
}
