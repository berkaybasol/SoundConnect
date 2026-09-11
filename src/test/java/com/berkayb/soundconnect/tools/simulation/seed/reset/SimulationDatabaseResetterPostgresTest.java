package com.berkayb.soundconnect.tools.simulation.seed.reset;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class SimulationDatabaseResetterPostgresTest {

	private static final List<String> MUTABLE_ROOTS = List.of(
			"tbl_user",
			"tbl_band",
			"tbl_collab_actor",
			"tbl_collab_notification_outbox",
			"tbl_dm_conversation",
			"tbl_dm_message",
			"tbl_event_performer_notification_outbox",
			"tbl_media_asset",
			"tbl_notification",
			"tbl_notification_receipt",
			"tbl_overthinking_notification_outbox",
			"tbl_profile_media",
			"tbl_table_group",
			"tbl_table_group_notification_outbox",
			"tbl_tracks",
			"tbl_venue_analytics_presence",
			"tbl_venue_analytics_receipt",
			"tbl_venue_analytics_recent_detail",
			"tbl_venue_analytics_state",
			"tbl_venue_suggestion"
	);

	private static final List<String> CATALOG_TABLES = List.of(
			"tbl_role",
			"tbl_permissions",
			"role_permissions",
			"tbl_city",
			"tbl_district",
			"tbl_neighborhood",
			"tbl_instrument",
			"tbl_backline_category",
			"soundconnect_schema_migrations"
	);

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("simulation_reset_test")
			.withUsername("simulation_reset_test")
			.withPassword("simulation_reset_test")
			.withReuse(false);

	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void schemaAndFixtures() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbcTemplate = new JdbcTemplate(dataSource);
		jdbcTemplate.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");

		for (String table : CATALOG_TABLES) {
			jdbcTemplate.execute("CREATE TABLE \"" + table + "\" (id BIGSERIAL PRIMARY KEY)");
			jdbcTemplate.execute("INSERT INTO \"" + table + "\" DEFAULT VALUES");
		}
		for (String table : MUTABLE_ROOTS) {
			jdbcTemplate.execute("CREATE TABLE \"" + table + "\" (id BIGSERIAL PRIMARY KEY)");
			jdbcTemplate.execute("INSERT INTO \"" + table + "\" DEFAULT VALUES");
		}

		jdbcTemplate.execute("CREATE TABLE \"tbl_band_member\" ("
				+ "id BIGSERIAL PRIMARY KEY, "
				+ "band_id BIGINT NOT NULL REFERENCES \"tbl_band\"(id))");
		jdbcTemplate.execute("INSERT INTO \"tbl_band_member\" (band_id) VALUES (1)");
	}

	@Test
	void clearsEveryMutableRootAndItsDependentsWhilePreservingCatalogs() {
		SimulationDatabaseResetter resetter = new SimulationDatabaseResetter(
				mock(SimulationRuntimeGuard.class),
				mock(SimulationFreshResetAuthorization.class),
				jdbcTemplate);

		resetter.resetApplicationData();

		for (String table : MUTABLE_ROOTS) {
			assertThat(rowCount(table)).as(table).isZero();
		}
		assertThat(rowCount("tbl_band_member")).isZero();
		for (String table : CATALOG_TABLES) {
			assertThat(rowCount(table)).as(table).isOne();
		}
		jdbcTemplate.execute("INSERT INTO \"tbl_band\" DEFAULT VALUES");
		assertThat(jdbcTemplate.queryForObject("SELECT id FROM \"tbl_band\"", Long.class))
				.isEqualTo(1L);
	}

	private long rowCount(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM \"" + table + "\"", Long.class);
	}
}
