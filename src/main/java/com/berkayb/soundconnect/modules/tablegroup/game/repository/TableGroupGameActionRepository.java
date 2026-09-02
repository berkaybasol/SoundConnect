package com.berkayb.soundconnect.modules.tablegroup.game.repository;

import com.berkayb.soundconnect.modules.tablegroup.game.entity.TableGroupGameAction;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameActionType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface TableGroupGameActionRepository extends JpaRepository<TableGroupGameAction, UUID> {
	Optional<TableGroupGameAction> findByGameIdAndActorUserIdAndRequestId(
			UUID gameId,
			UUID actorUserId,
			UUID requestId
	);

	Optional<TableGroupGameAction> findByGameIdAndRoundNumberAndActorUserId(
			UUID gameId,
			int roundNumber,
			UUID actorUserId
	);

	List<TableGroupGameAction> findByGameIdAndRoundNumberOrderByCreatedAtAscIdAsc(
			UUID gameId,
			int roundNumber
	);

	List<TableGroupGameAction> findByGameIdAndRevealedTrueOrderByRoundNumberAscCreatedAtAscIdAsc(UUID gameId);
	List<TableGroupGameAction> findByGameIdInOrderByGameIdAscRoundNumberAscCreatedAtAscIdAsc(
			Collection<UUID> gameIds
	);
	List<TableGroupGameAction> findByGameIdInAndRevealedTrueOrderByGameIdAscRoundNumberAscCreatedAtAscIdAsc(
			Collection<UUID> gameIds
	);

	boolean existsByGameIdAndActorUserIdAndAction(
			UUID gameId,
			UUID actorUserId,
			TableGroupGameActionType action
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update TableGroupGameAction action set action.revealed = true where action.gameId = :gameId and action.roundNumber = :round")
	int revealRound(@Param("gameId") UUID gameId, @Param("round") int round);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from TableGroupGameAction action where action.gameId in :gameIds")
	int deleteAllByGameIdIn(@Param("gameIds") Collection<UUID> gameIds);
}
