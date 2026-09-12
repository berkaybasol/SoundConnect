package com.berkayb.soundconnect.tools.simulation.seed.profile;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Testcontainers(disabledWithoutDocker = true)
class SimulationMusicianLegacyStageNameCleanerPostgresTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("simulation_stage_name_cleanup")
			.withUsername("soundconnect")
			.withPassword("soundconnect")
			.withReuse(false);

	private JdbcTemplate sql;
	private SimulationRuntimeGuard runtimeGuard;
	private SimulationMusicianLegacyStageNameCleaner cleaner;

	@BeforeEach
	void setUp() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		sql = new JdbcTemplate(dataSource);
		sql.execute("drop table if exists tbl_collab_actor");
		sql.execute("drop table if exists tbl_musician_profile");
		sql.execute("drop table if exists tbl_user");
		sql.execute("create table tbl_user(id uuid primary key, user_name varchar(64) not null)");
		sql.execute("""
				create table tbl_musician_profile(
					id uuid primary key,
					user_id uuid not null unique,
					stage_name varchar(255),
					updated_at timestamp without time zone
				)
				""");
		sql.execute("""
				create table tbl_collab_actor(
					id uuid primary key,
					profile_type varchar(24) not null,
					source_profile_id uuid not null,
					display_name varchar(120) not null,
					updated_at timestamp without time zone
				)
				""");
		runtimeGuard = mock(SimulationRuntimeGuard.class);
		cleaner = new SimulationMusicianLegacyStageNameCleaner(
				runtimeGuard, new NamedParameterJdbcTemplate(dataSource));
	}

	@Test
	void clearsOnlyExactManifestUsersAndIsIdempotent() {
		UUID seededWithStageName = UUID.randomUUID();
		UUID seededWithBlankStageName = UUID.randomUUID();
		UUID seededAlreadyClean = UUID.randomUUID();
		UUID unrelatedUser = UUID.randomUUID();
		UUID seededProfile = insert(seededWithStageName, "seeded-user", "Simulation Stage");
		UUID blankProfile = insert(seededWithBlankStageName, "blank-user", "");
		insert(seededAlreadyClean, "clean-user", null);
		UUID unrelatedProfile = insert(unrelatedUser, "unrelated-user", "Real User Legacy Stage");
		insertActor(seededProfile, "Simulation Stage");
		insertActor(blankProfile, "");
		insertActor(unrelatedProfile, "Real User Legacy Stage");

		List<UUID> manifestUsers = List.of(
				seededWithStageName, seededWithBlankStageName, seededAlreadyClean);
		assertThat(cleaner.clearForUserIds(manifestUsers)).isEqualTo(2);
		assertThat(stageName(seededWithStageName)).isNull();
		assertThat(stageName(seededWithBlankStageName)).isNull();
		assertThat(stageName(seededAlreadyClean)).isNull();
		assertThat(stageName(unrelatedUser)).isEqualTo("Real User Legacy Stage");
		assertThat(actorName(seededProfile)).isEqualTo("seeded-user");
		assertThat(actorName(blankProfile)).isEqualTo("blank-user");
		assertThat(actorName(unrelatedProfile)).isEqualTo("Real User Legacy Stage");

		assertThat(cleaner.clearForUserIds(manifestUsers)).isZero();
		verify(runtimeGuard, times(2)).assertRuntimeAllowed();
	}

	private UUID insert(UUID userId, String username, String stageName) {
		UUID profileId = UUID.randomUUID();
		sql.update("insert into tbl_user(id,user_name) values (?,?)", userId, username);
		sql.update("insert into tbl_musician_profile(id,user_id,stage_name,updated_at) values (?,?,?,?)",
				profileId, userId, stageName, Timestamp.from(Instant.parse("2026-09-11T00:00:00Z")));
		return profileId;
	}

	private void insertActor(UUID profileId, String displayName) {
		sql.update("""
				insert into tbl_collab_actor(id,profile_type,source_profile_id,display_name,updated_at)
				values (?,?,?,?,?)
				""", UUID.randomUUID(), "MUSICIAN", profileId, displayName,
				Timestamp.from(Instant.parse("2026-09-11T00:00:00Z")));
	}

	private String stageName(UUID userId) {
		return sql.queryForObject(
				"select stage_name from tbl_musician_profile where user_id=?", String.class, userId);
	}

	private String actorName(UUID profileId) {
		return sql.queryForObject(
				"select display_name from tbl_collab_actor where source_profile_id=?", String.class, profileId);
	}
}
