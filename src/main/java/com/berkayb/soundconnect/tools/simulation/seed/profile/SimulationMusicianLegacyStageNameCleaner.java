package com.berkayb.soundconnect.tools.simulation.seed.profile;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Removes stage names previously written by the local simulation seeder.
 *
 * <p>The cleanup is intentionally unavailable outside the guarded local simulation
 * runtime and receives only the already-validated musician user IDs from the active
 * world manifest. It is idempotent and never scans or mutates unrelated profiles.</p>
 */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SimulationMusicianLegacyStageNameCleaner {
	private static final String CLEAR_SQL = """
			update tbl_musician_profile
			set stage_name=null, updated_at=current_timestamp
			where user_id in (:userIds) and stage_name is not null
			""";
	private static final String RESYNC_COLLAB_ACTOR_SQL = """
			update tbl_collab_actor actor
			set display_name=account.user_name, updated_at=current_timestamp
			from tbl_musician_profile profile
			join tbl_user account on account.id=profile.user_id
			where profile.user_id in (:userIds)
			  and actor.profile_type='MUSICIAN'
			  and actor.source_profile_id=profile.id
			  and actor.display_name is distinct from account.user_name
			""";

	private final SimulationRuntimeGuard runtimeGuard;
	private final NamedParameterJdbcTemplate jdbc;

	@Transactional
	public int clearForUserIds(Collection<UUID> userIds) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(userIds, "userIds");
		Set<UUID> exactUserIds = new LinkedHashSet<>();
		for (UUID userId : userIds) {
			if (userId == null) throw new IllegalArgumentException("Simulation musician user id cannot be null");
			exactUserIds.add(userId);
		}
		if (exactUserIds.isEmpty()) return 0;
		var parameters = new MapSqlParameterSource("userIds", exactUserIds);
		int clearedStageNames = jdbc.update(CLEAR_SQL, parameters);
		// Collab actors are projections. Repair only the same manifest identities so
		// already-materialized listings stop carrying the removed simulation value.
		jdbc.update(RESYNC_COLLAB_ACTOR_SQL, parameters);
		return clearedStageNames;
	}
}
