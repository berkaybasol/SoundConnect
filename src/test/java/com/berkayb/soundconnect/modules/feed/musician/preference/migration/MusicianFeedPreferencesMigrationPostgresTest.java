package com.berkayb.soundconnect.modules.feed.musician.preference.migration;

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
class MusicianFeedPreferencesMigrationPostgresTest {
	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("musician_feed_preferences_migration")
			.withUsername("soundconnect").withPassword("soundconnect");

	private final UUID profileId = UUID.randomUUID();
	private final UUID cityId = UUID.randomUUID();

	@BeforeEach
	void schema() throws SQLException {
		execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
		execute("""
				CREATE TABLE tbl_musician_profile(id uuid PRIMARY KEY);
				CREATE TABLE tbl_city(id uuid PRIMARY KEY, name varchar(255) NOT NULL);
				INSERT INTO tbl_musician_profile(id) VALUES ('%s');
				INSERT INTO tbl_city(id, name) VALUES ('%s', 'İstanbul');
				""".formatted(profileId, cityId));
	}

	@Test
	void migrationIsAdditiveAndDoesNotInventAResidenceOrPreference() throws Exception {
		execute(migration());

		assertThat(number("SELECT count(*) FROM tbl_musician_feed_preferences")).isZero();
		execute("INSERT INTO tbl_musician_feed_preferences(musician_profile_id, opportunity_city_id) VALUES ('"
				+ profileId + "','" + cityId + "')");
		assertThat(number("SELECT version FROM tbl_musician_feed_preferences WHERE musician_profile_id='"
				+ profileId + "'")).isZero();
		assertThat(number("SELECT count(*) FROM soundconnect_schema_migrations WHERE migration_id="
				+ "'2026-09-11-musician-feed-preferences'")).isEqualTo(1);
	}

	@Test
	void rerunPreservesSavedCityAndRevision() throws Exception {
		execute(migration());
		execute("INSERT INTO tbl_musician_feed_preferences VALUES ('" + profileId + "','" + cityId + "',7)");

		execute(migration());

		assertThat(number("SELECT version FROM tbl_musician_feed_preferences WHERE musician_profile_id='"
				+ profileId + "'")).isEqualTo(7);
		assertThat(text("SELECT opportunity_city_id::text FROM tbl_musician_feed_preferences WHERE musician_profile_id='"
				+ profileId + "'")).isEqualTo(cityId.toString());
		assertThat(number("SELECT count(*) FROM pg_indexes WHERE indexname='ix_musician_feed_preferences_city'"))
				.isEqualTo(1);
	}

	@Test
	void cityDeletionClearsOnlyDiscoveryPreferenceAndProfileDeletionCascades() throws Exception {
		execute(migration());
		execute("INSERT INTO tbl_musician_feed_preferences VALUES ('" + profileId + "','" + cityId + "',4)");

		execute("DELETE FROM tbl_city WHERE id='" + cityId + "'");
		assertThat(number("SELECT count(*) FROM tbl_musician_feed_preferences WHERE opportunity_city_id IS NULL AND version=4"))
				.isEqualTo(1);
		execute("DELETE FROM tbl_musician_profile WHERE id='" + profileId + "'");
		assertThat(number("SELECT count(*) FROM tbl_musician_feed_preferences")).isZero();
	}

	@Test
	void invalidOrOverflowingRevisionsAndUnknownReferencesAreRejected() throws Exception {
		execute(migration());

		assertThatThrownBy(() -> execute("INSERT INTO tbl_musician_feed_preferences VALUES ('"
				+ profileId + "',NULL,-1)"))
				.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute("INSERT INTO tbl_musician_feed_preferences VALUES ('"
				+ profileId + "',NULL,9223372036854775807)"))
				.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute("INSERT INTO tbl_musician_feed_preferences VALUES ('"
				+ UUID.randomUUID() + "',NULL,0)"))
				.isInstanceOf(SQLException.class);
		assertThatThrownBy(() -> execute("INSERT INTO tbl_musician_feed_preferences VALUES ('"
				+ profileId + "','" + UUID.randomUUID() + "',0)"))
				.isInstanceOf(SQLException.class);
	}

	@Test
	void migrationNormalizesHibernateNamedForeignKeysWithoutLosingData() throws Exception {
		execute("""
				CREATE TABLE tbl_musician_feed_preferences(
				  musician_profile_id uuid PRIMARY KEY,
				  opportunity_city_id uuid NOT NULL,
				  version bigint,
				  CONSTRAINT fk_generated_profile FOREIGN KEY(musician_profile_id) REFERENCES tbl_musician_profile(id),
				  CONSTRAINT fk_generated_city FOREIGN KEY(opportunity_city_id) REFERENCES tbl_city(id)
				);
				INSERT INTO tbl_musician_feed_preferences VALUES ('%s','%s',NULL);
				""".formatted(profileId, cityId));

		execute(migration());

		assertThat(number("SELECT version FROM tbl_musician_feed_preferences")).isZero();
		execute("DELETE FROM tbl_city WHERE id='" + cityId + "'");
		assertThat(number("SELECT count(*) FROM tbl_musician_feed_preferences WHERE opportunity_city_id IS NULL"))
				.isEqualTo(1);
	}

	private static String migration() throws Exception {
		return Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-preferences.sql"));
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private void execute(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private long number(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getLong(1);
		}
	}

	private String text(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next();
			return result.getString(1);
		}
	}
}
