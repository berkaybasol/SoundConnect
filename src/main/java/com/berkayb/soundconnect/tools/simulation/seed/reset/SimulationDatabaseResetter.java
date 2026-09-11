package com.berkayb.soundconnect.tools.simulation.seed.reset;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Clears mutable application data for a disposable local simulation world.
 *
 * <p>The root-table allowlist is a fixed, quoted literal. It includes mutable
 * roots that are not reachable from {@code tbl_user} through foreign keys
 * (for example bands and polymorphic media). Catalog/reference tables are
 * deliberately absent. This class accepts no table/database input and executes
 * exactly one SQL statement.</p>
 */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true", matchIfMissing = false)
public final class SimulationDatabaseResetter {

	static final String RESET_SQL = "TRUNCATE TABLE "
			+ "\"tbl_user\", "
			+ "\"tbl_band\", "
			+ "\"tbl_collab_actor\", "
			+ "\"tbl_collab_notification_outbox\", "
			+ "\"tbl_dm_conversation\", "
			+ "\"tbl_dm_message\", "
			+ "\"tbl_event_performer_notification_outbox\", "
			+ "\"tbl_media_asset\", "
			+ "\"tbl_notification\", "
			+ "\"tbl_notification_receipt\", "
			+ "\"tbl_overthinking_notification_outbox\", "
			+ "\"tbl_profile_media\", "
			+ "\"tbl_table_group\", "
			+ "\"tbl_table_group_notification_outbox\", "
			+ "\"tbl_tracks\", "
			+ "\"tbl_venue_analytics_presence\", "
			+ "\"tbl_venue_analytics_receipt\", "
			+ "\"tbl_venue_analytics_recent_detail\", "
			+ "\"tbl_venue_analytics_state\", "
			+ "\"tbl_venue_suggestion\" "
			+ "RESTART IDENTITY CASCADE";

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationFreshResetAuthorization resetAuthorization;
	private final JdbcTemplate jdbcTemplate;

	public SimulationDatabaseResetter(
			SimulationRuntimeGuard runtimeGuard,
			SimulationFreshResetAuthorization resetAuthorization,
			JdbcTemplate jdbcTemplate
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.resetAuthorization = Objects.requireNonNull(resetAuthorization, "resetAuthorization");
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
	}

	public void resetApplicationData() {
		runtimeGuard.assertRuntimeAllowed();
		resetAuthorization.assertFreshResetAuthorized();
		jdbcTemplate.execute(RESET_SQL);
	}
}
