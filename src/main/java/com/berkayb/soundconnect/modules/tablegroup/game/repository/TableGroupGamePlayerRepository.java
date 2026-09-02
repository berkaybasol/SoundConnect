package com.berkayb.soundconnect.modules.tablegroup.game.repository;

import com.berkayb.soundconnect.modules.tablegroup.game.entity.TableGroupGamePlayer;
import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGamePlayerStatus;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.*;

public interface TableGroupGamePlayerRepository extends JpaRepository<TableGroupGamePlayer, UUID> {
	List<TableGroupGamePlayer> findByGameIdOrderByJoinedAtAscIdAsc(UUID gameId);
	List<TableGroupGamePlayer> findByGameIdInOrderByGameIdAscJoinedAtAscIdAsc(Collection<UUID> gameIds);
	Optional<TableGroupGamePlayer> findByGameIdAndUserId(UUID gameId, UUID userId);
	List<TableGroupGamePlayer> findByGameIdAndStatusOrderByJoinedAtAscIdAsc(
			UUID gameId,
			TableGroupGamePlayerStatus status
	);
	long countByGameIdAndStatus(UUID gameId, TableGroupGamePlayerStatus status);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from TableGroupGamePlayer player where player.gameId in :gameIds")
	int deleteAllByGameIdIn(@Param("gameIds") Collection<UUID> gameIds);
}
