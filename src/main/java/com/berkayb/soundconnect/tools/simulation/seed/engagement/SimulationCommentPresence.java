package com.berkayb.soundconnect.tools.simulation.seed.engagement;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Read-only idempotency seam for an API that intentionally has no client request key. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public class SimulationCommentPresence {

	private final NamedParameterJdbcTemplate jdbc;

	public SimulationCommentPresence(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
	}

	@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, timeout = 5)
	public boolean exists(UUID actorId, EngagementTargetType type, UUID targetId, String text) {
		if (actorId == null || type == null || targetId == null || text == null || text.isBlank()) {
			throw new IllegalArgumentException("Complete comment identity is required");
		}
		Boolean result = jdbc.queryForObject("""
				select exists(
				    select 1 from tbl_comment
				    where user_id=:actorId and target_type=:targetType and target_id=:targetId
				      and parent_comment_id is null and not is_deleted and text=:text
				)
				""", new MapSqlParameterSource()
				.addValue("actorId", actorId)
				.addValue("targetType", type.name())
				.addValue("targetId", targetId)
				.addValue("text", text.trim()), Boolean.class);
		return Boolean.TRUE.equals(result);
	}
}
