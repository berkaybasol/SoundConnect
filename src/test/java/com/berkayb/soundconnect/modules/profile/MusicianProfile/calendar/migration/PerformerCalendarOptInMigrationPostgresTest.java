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

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class PerformerCalendarOptInMigrationPostgresTest {
	@Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("performer_calendar_migration").withUsername("soundconnect").withPassword("soundconnect");
	private final UUID untouched = UUID.randomUUID(), enabled = UUID.randomUUID(), disabled = UUID.randomUUID(), band = UUID.randomUUID();
	@BeforeEach void schema() throws Exception {
		execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
		execute("""
				CREATE TABLE tbl_musician_profile (id uuid PRIMARY KEY);
				CREATE TABLE tbl_band (id uuid PRIMARY KEY);
				CREATE TABLE tbl_event (id uuid PRIMARY KEY, musician_profile_id uuid, band_id uuid,
				 event_date date, start_time time, performer_approval_status varchar(20));
				CREATE TABLE tbl_band_member (id uuid PRIMARY KEY, user_id uuid, band_id uuid, status varchar(30));
				INSERT INTO tbl_musician_profile VALUES ('%s'), ('%s'), ('%s');
				INSERT INTO tbl_band VALUES ('%s');
				INSERT INTO tbl_event (id, band_id, performer_approval_status) VALUES ('%s', '%s', 'APPROVED');
				""".formatted(untouched, enabled, disabled, band, UUID.randomUUID(), band));
		execute(Files.readString(Path.of("scripts/db/2026-09-05-musician-calendar.sql")));
		execute("UPDATE tbl_musician_calendar_settings SET version = 2 WHERE musician_profile_id = '" + enabled + "'");
		execute("UPDATE tbl_musician_calendar_settings SET visible = false, version = 3 WHERE musician_profile_id = '" + disabled + "'");
	}

	@Test void correctionOnlyChangesUntouchedDefaultsAndRerunPreservesExplicitChoicesAndEvents() throws Exception {
		execute(migration()); execute(migration());
		assertThat(number("SELECT count(*) FROM tbl_musician_calendar_settings WHERE NOT visible AND version = 1")).isEqualTo(1);
		assertThat(number("SELECT count(*) FROM tbl_musician_calendar_settings WHERE visible AND version = 2")).isEqualTo(1);
		assertThat(number("SELECT count(*) FROM tbl_musician_calendar_settings WHERE NOT visible AND version = 3")).isEqualTo(1);
		assertThat(number("SELECT count(*) FROM tbl_band_calendar_settings WHERE NOT visible AND version = 0")).isEqualTo(1);
		assertThat(number("SELECT count(*) FROM tbl_event WHERE performer_approval_status = 'APPROVED'")).isEqualTo(1);
		execute("UPDATE tbl_band_calendar_settings SET visible = true, version = 2");
		execute(migration());
		assertThat(number("SELECT count(*) FROM tbl_band_calendar_settings WHERE visible AND version = 2")).isEqualTo(1);
	}

	@Test void freshDatabaseInsertsAreOffAndConstraintsCascade() throws Exception {
		execute(migration());
		UUID fresh = UUID.randomUUID();
		execute("INSERT INTO tbl_musician_profile VALUES ('" + fresh + "'); INSERT INTO tbl_musician_calendar_settings (musician_profile_id) VALUES ('" + fresh + "')");
		assertThat(number("SELECT count(*) FROM tbl_musician_calendar_settings WHERE NOT visible AND version = 0")).isEqualTo(1);
		assertThatThrownBy(() -> execute("UPDATE tbl_band_calendar_settings SET visible = NULL")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute("UPDATE tbl_band_calendar_settings SET version = -1")).isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute("INSERT INTO tbl_band_calendar_settings (band_id) VALUES ('" + UUID.randomUUID() + "')")).isInstanceOf(SQLException.class);
		execute("DELETE FROM tbl_band WHERE id = '" + band + "'");
		assertThat(number("SELECT count(*) FROM tbl_band_calendar_settings")).isZero();
	}

	@Test void existingHibernateTableIsNormalizedWithoutLosingSavedBandPreference() throws Exception {
		execute("""
				CREATE TABLE tbl_band_calendar_settings (band_id uuid PRIMARY KEY, visible boolean NOT NULL, version bigint NOT NULL,
				CONSTRAINT generated_fk FOREIGN KEY (band_id) REFERENCES tbl_band(id));
				INSERT INTO tbl_band_calendar_settings VALUES ('%s', true, 5);
				""".formatted(band));
		execute(migration());
		assertThat(number("SELECT count(*) FROM tbl_band_calendar_settings WHERE visible AND version = 5")).isEqualTo(1);
		execute("DELETE FROM tbl_band WHERE id = '" + band + "'");
		assertThat(number("SELECT count(*) FROM tbl_band_calendar_settings")).isZero();
	}

	private String migration() throws Exception { return Files.readString(Path.of("scripts/db/2026-09-05-performer-calendar-opt-in.sql")); }
	private Connection connection() throws SQLException { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
	private void execute(String sql) throws SQLException { try (var connection = connection(); var statement = connection.createStatement()) { statement.execute(sql); } }
	private long number(String sql) throws SQLException {
		try (var connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next(); return result.getLong(1);
		}
	}
}
