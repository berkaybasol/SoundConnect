package com.berkayb.soundconnect.modules.profile.MusicianProfile.calendar.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MusicianCalendarMigrationPostgresTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("musician_calendar_migration").withUsername("soundconnect").withPassword("soundconnect");
	private final UUID profile = UUID.randomUUID();
	@BeforeEach
	void schema() throws SQLException {
		execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
		execute("""
				CREATE TABLE tbl_musician_profile (id uuid PRIMARY KEY);
				CREATE TABLE tbl_event (id uuid PRIMARY KEY, musician_profile_id uuid, band_id uuid,
				    event_date date, start_time time, performer_approval_status varchar(20));
				CREATE TABLE tbl_band_member (id uuid PRIMARY KEY, user_id uuid, band_id uuid, status varchar(30));
				INSERT INTO tbl_musician_profile VALUES ('%s');
				""".formatted(profile));
	}

	@Test
	void migrationDefaultsExistingProfilesToVisibleAndPreservesPreferencesOnRerun() throws Exception {
		execute(migration());
		assertThat(number("SELECT count(*) FROM tbl_musician_calendar_settings WHERE visible AND version = 0")).isEqualTo(1);
		execute("UPDATE tbl_musician_calendar_settings SET visible = false, version = 2");
		execute(migration());
		assertThat(number("SELECT count(*) FROM tbl_musician_calendar_settings WHERE NOT visible AND version = 2")).isEqualTo(1);
		assertThat(number("SELECT count(*) FROM pg_indexes WHERE indexname in ('ix_event_musician_calendar','ix_event_band_calendar','ix_band_member_calendar','ix_event_approved_calendar_date')")).isEqualTo(4);
	}

	@Test
	void invalidSettingsAreRejectedAndDeletingProfileCascades() throws Exception {
		execute(migration());
		assertThatThrownBy(() -> execute("UPDATE tbl_musician_calendar_settings SET version = -1")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute("UPDATE tbl_musician_calendar_settings SET visible = NULL")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute("INSERT INTO tbl_musician_calendar_settings (musician_profile_id) VALUES ('" + UUID.randomUUID() + "')"))
				.isInstanceOf(SQLException.class);
		execute("DELETE FROM tbl_musician_profile WHERE id = '" + profile + "'");
		assertThat(number("SELECT count(*) FROM tbl_musician_calendar_settings")).isZero();
	}

	@Test
	void newlyCreatedProfilesReceiveTheSameDefaultOnTheirFirstSettingsWrite() throws Exception {
		execute(migration());
		UUID fresh = UUID.randomUUID();
		execute("INSERT INTO tbl_musician_profile VALUES ('" + fresh + "')");
		execute("INSERT INTO tbl_musician_calendar_settings (musician_profile_id) VALUES ('" + fresh + "')");
		assertThat(number("SELECT count(*) FROM tbl_musician_calendar_settings WHERE visible AND version = 0")).isEqualTo(2);
	}

	@Test
	void migrationNormalizesHibernateCreatedTableAndArbitrarilyNamedForeignKey() throws Exception {
		execute("""
				CREATE TABLE tbl_musician_calendar_settings (
				    musician_profile_id uuid PRIMARY KEY,
				    visible boolean NOT NULL,
				    version bigint NOT NULL,
				    CONSTRAINT fk_generated_8f7 FOREIGN KEY (musician_profile_id) REFERENCES tbl_musician_profile(id)
				);
				INSERT INTO tbl_musician_calendar_settings VALUES ('%s', false, 4);
				""".formatted(profile));
		execute(migration());
		assertThat(number("SELECT count(*) FROM tbl_musician_calendar_settings WHERE NOT visible AND version = 4")).isEqualTo(1);
		execute("DELETE FROM tbl_musician_profile WHERE id = '" + profile + "'");
		assertThat(number("SELECT count(*) FROM tbl_musician_calendar_settings")).isZero();
	}

	private String migration() throws Exception { return Files.readString(Path.of("scripts/db/2026-09-05-musician-calendar.sql")); }
	private Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
	private void execute(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) { statement.execute(sql); }
	}
	private long number(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next(); return result.getLong(1);
		}
	}
}
