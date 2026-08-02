package com.berkayb.soundconnect.modules.studio.migration;

import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategory;
import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategoryRequest;
import com.berkayb.soundconnect.modules.backline.catalog.entity.BacklineCategoryRequestChild;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipment;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentAvailabilityCommand;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentDay;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentFeature;
import com.berkayb.soundconnect.modules.studio.equipment.entity.StudioEquipmentPhoto;
import com.berkayb.soundconnect.modules.studio.reservation.entity.StudioRoomOccupancy;
import com.berkayb.soundconnect.modules.studio.reservation.entity.StudioRoomReservation;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoom;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoomFeature;
import com.berkayb.soundconnect.modules.studio.room.entity.StudioRoomPhoto;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class StudioDomainMigrationPostgresTest {

	private static final String OWNER_ID = "00000000-0000-0000-0000-000000000101";
	private static final String REQUESTER_ID = "00000000-0000-0000-0000-000000000102";
	private static final String STUDIO_ID = "00000000-0000-0000-0000-000000000201";
	private static final String ROOM_ID = "00000000-0000-0000-0000-000000000301";
	private static final String EQUIPMENT_ID = "00000000-0000-0000-0000-000000000401";
	private static final String DYNAMIC_MIC_CATEGORY_ID = "20000000-0000-0000-0000-000000000055";
	private static final String ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000111";
	private static final String OWNER_ROLE_ID = "00000000-0000-0000-0000-000000000112";

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_studio_migration")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void createMinimalPrerequisiteSchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					DROP TABLE IF EXISTS tbl_studio_applications CASCADE;
					DROP TABLE IF EXISTS tbl_backline_category_request_child CASCADE;
					DROP TABLE IF EXISTS tbl_backline_category_request CASCADE;
					DROP TABLE IF EXISTS tbl_studio_equipment_availability_change CASCADE;
					DROP TABLE IF EXISTS tbl_studio_equipment_day CASCADE;
					DROP TABLE IF EXISTS tbl_studio_equipment_photo CASCADE;
					DROP TABLE IF EXISTS tbl_studio_equipment_feature CASCADE;
					DROP TABLE IF EXISTS tbl_studio_equipment CASCADE;
					DROP TABLE IF EXISTS tbl_studio_room_occupancy CASCADE;
					DROP TABLE IF EXISTS tbl_studio_room_reservation CASCADE;
					DROP TABLE IF EXISTS tbl_studio_room_photo CASCADE;
					DROP TABLE IF EXISTS tbl_studio_room_feature CASCADE;
					DROP TABLE IF EXISTS tbl_studio_room CASCADE;
					DROP TABLE IF EXISTS studio_profile_spotify_tracks CASCADE;
					DROP TABLE IF EXISTS tbl_backline_category CASCADE;
					DROP TABLE IF EXISTS role_permissions CASCADE;
					DROP TABLE IF EXISTS tbl_permissions CASCADE;
					DROP TABLE IF EXISTS tbl_role CASCADE;
					DROP TABLE IF EXISTS tbl_media_asset CASCADE;
					DROP TABLE IF EXISTS tbl_studio_profile CASCADE;
					DROP TABLE IF EXISTS tbl_neighborhood CASCADE;
					DROP TABLE IF EXISTS tbl_district CASCADE;
					DROP TABLE IF EXISTS tbl_city CASCADE;
					DROP TABLE IF EXISTS tbl_user CASCADE;
					DROP SEQUENCE IF EXISTS tbl_user_public_code_seq CASCADE;
					DROP FUNCTION IF EXISTS soundconnect_assign_user_public_code() CASCADE;
					DROP FUNCTION IF EXISTS soundconnect_validate_backline_category_parent() CASCADE;
					DROP FUNCTION IF EXISTS soundconnect_validate_reservation_occupancy() CASCADE;
					DROP FUNCTION IF EXISTS soundconnect_validate_studio_equipment_category() CASCADE;
					DROP FUNCTION IF EXISTS soundconnect_validate_studio_equipment_day_capacity() CASCADE;
					DROP FUNCTION IF EXISTS soundconnect_validate_studio_equipment_total_quantity() CASCADE;
					DROP FUNCTION IF EXISTS soundconnect_validate_backline_category_request_parent() CASCADE;
					DROP FUNCTION IF EXISTS soundconnect_validate_backline_request_child() CASCADE;
					""");

			statement.execute("""
					CREATE TABLE tbl_user (
					    id uuid PRIMARY KEY,
					    created_at timestamp without time zone,
					    updated_at timestamp without time zone
					)
					""");
			statement.execute("CREATE TABLE tbl_city (id uuid PRIMARY KEY, name varchar(255) NOT NULL)");
			statement.execute("""
					CREATE TABLE tbl_district (
					    id uuid PRIMARY KEY,
					    name varchar(255) NOT NULL,
					    city_id uuid NOT NULL REFERENCES tbl_city(id)
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_neighborhood (
					    id uuid PRIMARY KEY,
					    name varchar(255) NOT NULL,
					    district_id uuid NOT NULL REFERENCES tbl_district(id)
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_studio_profile (
					    id uuid PRIMARY KEY,
					    user_id uuid NOT NULL UNIQUE REFERENCES tbl_user(id),
					    created_at timestamp without time zone,
					    updated_at timestamp without time zone
					)
					""");
			statement.execute("CREATE TABLE tbl_media_asset (id uuid PRIMARY KEY)");
			statement.execute("""
					CREATE TABLE tbl_permissions (
					    id uuid PRIMARY KEY,
					    created_at timestamp without time zone,
					    updated_at timestamp without time zone,
					    name varchar(255) NOT NULL UNIQUE
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_role (
					    id uuid PRIMARY KEY,
					    created_at timestamp without time zone,
					    updated_at timestamp without time zone,
					    name varchar(255) NOT NULL UNIQUE
					)
					""");
			statement.execute("""
					CREATE TABLE role_permissions (
					    role_id uuid NOT NULL REFERENCES tbl_role(id),
					    permission_id uuid NOT NULL REFERENCES tbl_permissions(id),
					    PRIMARY KEY (role_id, permission_id)
					)
					""");
			statement.execute("""
					INSERT INTO tbl_role (id, name)
					VALUES
					    ('%s', 'ROLE_ADMIN'),
					    ('%s', 'ROLE_OWNER')
					""".formatted(ADMIN_ROLE_ID, OWNER_ROLE_ID));
		}
	}

	@Test
	void migrationIsRerunnableAndBackfillsExistingProfilesAndUsers() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO tbl_user (id) VALUES ('" + OWNER_ID + "')");
			statement.execute("""
					INSERT INTO tbl_studio_profile (id, user_id)
					VALUES ('%s', '%s')
					""".formatted(STUDIO_ID, OWNER_ID));
		}

		String migration = migrationSql();
		assertThat(migration).contains("BEGIN;", "COMMIT;", "btree_gist");
		executeMigration(migration);

		String firstPublicCode;
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			firstPublicCode = singleString(statement,
					"SELECT public_code FROM tbl_user WHERE id = '" + OWNER_ID + "'");
			assertThat(firstPublicCode).matches("SC-[0-9A-F]{20}");
			assertThat(singleString(statement, """
					SELECT time_zone || ':' || version::text || ':' || spotify_tracks::text
					  FROM tbl_studio_profile
					 WHERE id = '%s'
					""".formatted(STUDIO_ID))).isEqualTo("Europe/Istanbul:0:[]");
			assertThat(singleInt(statement,
					"SELECT count(*) FROM tbl_backline_category WHERE parent_id IS NULL")).isEqualTo(10);
			assertThat(singleInt(statement,
					"SELECT count(*) FROM tbl_backline_category WHERE parent_id IS NOT NULL")).isEqualTo(73);
			assertThat(singleInt(statement, """
					SELECT count(*) FROM tbl_permissions
					 WHERE name = 'MANAGE_BACKLINE_CATALOG'
					""")).isEqualTo(1);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM role_permissions rp
					  JOIN tbl_role r ON r.id = rp.role_id
					  JOIN tbl_permissions p ON p.id = rp.permission_id
					 WHERE r.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
					   AND p.name = 'MANAGE_BACKLINE_CATALOG'
					""")).isEqualTo(2);
		}

		// An uncertain deployment result may cause the operator to rerun the script.
		executeMigration(migration);

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement,
					"SELECT public_code FROM tbl_user WHERE id = '" + OWNER_ID + "'"))
					.isEqualTo(firstPublicCode);
			assertThat(singleInt(statement,
					"SELECT count(*) FROM tbl_backline_category")).isEqualTo(83);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM role_permissions rp
					  JOIN tbl_role r ON r.id = rp.role_id
					  JOIN tbl_permissions p ON p.id = rp.permission_id
					 WHERE r.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
					   AND p.name = 'MANAGE_BACKLINE_CATALOG'
					""")).isEqualTo(2);
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					 WHERE conname = 'ex_studio_room_occupancy_no_overlap'
					   AND contype = 'x'
					   AND convalidated
					""")).isEqualTo(1);

			statement.execute("INSERT INTO tbl_user (id, public_code) VALUES "
					+ "('" + REQUESTER_ID + "', NULL)");
			assertThat(singleString(statement,
					"SELECT public_code FROM tbl_user WHERE id = '" + REQUESTER_ID + "'"))
					.matches("SC-[0-9A-F]{20}")
					.isNotEqualTo(firstPublicCode);
		}
	}

	@Test
	void migrationColumnsMatchTheFinalJpaEntityMappings() throws Exception {
		executeMigration(migrationSql());

		try (Connection connection = connection()) {
			assertEntityColumns(connection,
					BacklineCategory.class,
					BacklineCategoryRequest.class,
					BacklineCategoryRequestChild.class,
					StudioRoom.class,
					StudioRoomFeature.class,
					StudioRoomPhoto.class,
					StudioRoomReservation.class,
					StudioRoomOccupancy.class,
					StudioEquipment.class,
					StudioEquipmentFeature.class,
					StudioEquipmentPhoto.class,
					StudioEquipmentDay.class,
					StudioEquipmentAvailabilityCommand.class
			);
		}
	}

	@Test
	void activeOccupancyUsesHalfOpenRangesAndRejectsOverlap() throws Exception {
		executeMigration(migrationSql());
		seedStudioAndRoom();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tbl_studio_room_reservation
					    (id, room_id, requester_id, starts_at, ends_at, status,
					     approval_required_snapshot, currency_snapshot, client_request_id)
					VALUES
					    ('00000000-0000-0000-0000-000000000501', '%s', '%s',
					     TIMESTAMPTZ '2026-08-01 10:00:00+03', TIMESTAMPTZ '2026-08-01 12:00:00+03',
					     'CONFIRMED', false, 'TRY', '00000000-0000-0000-0000-000000000601')
					""".formatted(ROOM_ID, REQUESTER_ID));
			statement.execute("""
					INSERT INTO tbl_studio_room_occupancy
					    (id, room_id, reservation_id, type, starts_at, ends_at, active, created_by)
					VALUES
					    ('00000000-0000-0000-0000-000000000701', '%s',
					     '00000000-0000-0000-0000-000000000501', 'RESERVATION',
					     TIMESTAMPTZ '2026-08-01 10:00:00+03', TIMESTAMPTZ '2026-08-01 12:00:00+03',
					     true, '%s')
					""".formatted(ROOM_ID, OWNER_ID));

			// [10:00,12:00) and [12:00,13:00) touch but do not overlap.
			statement.execute("""
					INSERT INTO tbl_studio_room_occupancy
					    (id, room_id, client_request_id, type, starts_at, ends_at, active, created_by)
					VALUES
					    ('00000000-0000-0000-0000-000000000702', '%s',
					     '00000000-0000-0000-0000-000000000602', 'MANUAL_BLOCK',
					     TIMESTAMPTZ '2026-08-01 12:00:00+03', TIMESTAMPTZ '2026-08-01 13:00:00+03',
					     true, '%s')
					""".formatted(ROOM_ID, OWNER_ID));

			assertSqlState("23P01", () -> statement.execute("""
					INSERT INTO tbl_studio_room_occupancy
					    (id, room_id, client_request_id, type, starts_at, ends_at, active, created_by)
					VALUES
					    ('00000000-0000-0000-0000-000000000703', '%s',
					     '00000000-0000-0000-0000-000000000603', 'MANUAL_BLOCK',
					     TIMESTAMPTZ '2026-08-01 11:00:00+03', TIMESTAMPTZ '2026-08-01 13:00:00+03',
					     true, '%s')
					""".formatted(ROOM_ID, OWNER_ID)));

			statement.execute("""
					UPDATE tbl_studio_room_occupancy
					   SET active = false, released_at = CURRENT_TIMESTAMP, released_by = '%s'
					 WHERE id = '00000000-0000-0000-0000-000000000701'
					""".formatted(OWNER_ID));
			statement.execute("""
					INSERT INTO tbl_studio_room_occupancy
					    (id, room_id, client_request_id, type, starts_at, ends_at, active, created_by)
					VALUES
					    ('00000000-0000-0000-0000-000000000704', '%s',
					     '00000000-0000-0000-0000-000000000604', 'MANUAL_BLOCK',
					     TIMESTAMPTZ '2026-08-01 10:00:00+03', TIMESTAMPTZ '2026-08-01 12:00:00+03',
					     true, '%s')
					""".formatted(ROOM_ID, OWNER_ID));
			assertThat(singleInt(statement, """
					SELECT count(*) FROM tbl_studio_room_occupancy WHERE active
					""")).isEqualTo(2);
		}
	}

	@Test
	void equipmentCalendarCapacityIgnoresExpiredStudioLocalDaysButProtectsTodayAndFuture() throws Exception {
		executeMigration(migrationSql());
		seedStudioAndRoom();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tbl_studio_equipment
					    (id, studio_profile_id, creation_client_request_id, creation_payload_hash,
					     category_id, name, total_quantity)
					VALUES ('%s', '%s', '00000000-0000-0000-0000-000000000621',
					        repeat('a', 64), '%s', 'Shure SM58', 3)
					""".formatted(EQUIPMENT_ID, STUDIO_ID, DYNAMIC_MIC_CATEGORY_ID));

			// Expired projection state is not part of the forward-looking inventory
			// capacity invariant, even when it used the previous full quantity.
			statement.execute("""
					INSERT INTO tbl_studio_equipment_day
					    (id, equipment_id, availability_date, busy_count, maintenance_count)
					VALUES
					    ('00000000-0000-0000-0000-000000000801', '%s',
					     (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Istanbul')::date - 1, 2, 1)
					""".formatted(EQUIPMENT_ID));
			statement.execute("UPDATE tbl_studio_equipment SET total_quantity = 2 WHERE id = '"
					+ EQUIPMENT_ID + "'");

			statement.execute("""
					INSERT INTO tbl_studio_equipment_day
					    (id, equipment_id, availability_date, busy_count, maintenance_count)
					VALUES
					    ('00000000-0000-0000-0000-000000000802', '%s',
					     (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Istanbul')::date + 1, 1, 1)
					""".formatted(EQUIPMENT_ID));

			assertSqlState("23514", () -> statement.execute("""
					INSERT INTO tbl_studio_equipment_day
					    (id, equipment_id, availability_date, busy_count, maintenance_count)
					VALUES
					    ('00000000-0000-0000-0000-000000000803', '%s',
					     (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Istanbul')::date + 2, 2, 1)
					""".formatted(EQUIPMENT_ID)));
			assertSqlState("23514", () -> statement.execute("""
					UPDATE tbl_studio_equipment SET total_quantity = 1 WHERE id = '%s'
					""".formatted(EQUIPMENT_ID)));

			statement.execute("""
					UPDATE tbl_studio_equipment_day
					   SET maintenance_count = 0
					 WHERE equipment_id = '%s'
					   AND availability_date =
					       (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Istanbul')::date + 1
					""".formatted(EQUIPMENT_ID));
			statement.execute("UPDATE tbl_studio_equipment SET total_quantity = 1 WHERE id = '"
					+ EQUIPMENT_ID + "'");
			assertThat(singleInt(statement,
					"SELECT total_quantity FROM tbl_studio_equipment WHERE id = '" + EQUIPMENT_ID + "'"))
					.isEqualTo(1);
		}
	}

	@Test
	void migrationPurgesExpiredEquipmentProjectionsButRetainsImmutableCommandAudit() throws Exception {
		executeMigration(migrationSql());
		seedStudioAndRoom();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tbl_studio_equipment
					    (id, studio_profile_id, creation_client_request_id, creation_payload_hash,
					     category_id, name, total_quantity)
					VALUES ('%s', '%s', '00000000-0000-0000-0000-000000000624',
					        repeat('a', 64), '%s', 'Shure SM58', 2)
					""".formatted(EQUIPMENT_ID, STUDIO_ID, DYNAMIC_MIC_CATEGORY_ID));
			statement.execute("""
					INSERT INTO tbl_studio_equipment_day
					    (id, equipment_id, availability_date, busy_count, maintenance_count)
					VALUES ('00000000-0000-0000-0000-000000000811', '%s',
					        (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Istanbul')::date - 1, 1, 0)
					""".formatted(EQUIPMENT_ID));
			statement.execute("""
					INSERT INTO tbl_studio_equipment_availability_change
					    (id, equipment_id, actor_id, start_date, end_date,
					     source_bucket, target_bucket, quantity, client_request_id)
					VALUES ('00000000-0000-0000-0000-000000000812', '%s', '%s',
					        (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Istanbul')::date - 1,
					        (CURRENT_TIMESTAMP AT TIME ZONE 'Europe/Istanbul')::date - 1,
					        'AVAILABLE', 'BUSY', 1,
					        '00000000-0000-0000-0000-000000000813')
					""".formatted(EQUIPMENT_ID, OWNER_ID));
		}

		// A deployment rerun performs bounded-state cleanup without deleting the
		// immutable command log needed for audit and exact retry responses.
		executeMigration(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_studio_equipment_day WHERE equipment_id = '"
					+ EQUIPMENT_ID + "'"))
					.isZero();
			assertThat(singleInt(statement, """
					SELECT count(*) FROM tbl_studio_equipment_availability_change
					 WHERE equipment_id = '%s'
					""".formatted(EQUIPMENT_ID)))
					.isEqualTo(1);
		}
	}

	@Test
	void positionalChecksEnforceRoomAndEquipmentUiLimits() throws Exception {
		executeMigration(migrationSql());
		seedStudioAndRoom();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertSqlState("23514", () -> statement.execute("""
					INSERT INTO studio_profile_spotify_tracks (studio_profile_id, position, track_id)
					VALUES ('%s', 50, 'spotify-track-over-limit')
					""".formatted(STUDIO_ID)));
			assertSqlState("23514", () -> statement.execute("""
					UPDATE tbl_studio_profile SET spotify_tracks = '{}'::jsonb WHERE id = '%s'
					""".formatted(STUDIO_ID)));
			statement.execute("""
					INSERT INTO tbl_media_asset (id)
					VALUES ('00000000-0000-0000-0000-000000000901')
					""");
			statement.execute("""
					INSERT INTO tbl_studio_equipment
					    (id, studio_profile_id, creation_client_request_id, creation_payload_hash,
					     category_id, name, total_quantity)
					VALUES ('%s', '%s', '00000000-0000-0000-0000-000000000622',
					        repeat('b', 64), '%s', 'Shure SM58', 1)
					""".formatted(EQUIPMENT_ID, STUDIO_ID, DYNAMIC_MIC_CATEGORY_ID));

			assertSqlState("23514", () -> statement.execute("""
					INSERT INTO tbl_studio_room_feature (id, room_id, label, order_index)
					VALUES ('00000000-0000-0000-0000-000000000911', '%s', 'Ninth feature', 8)
					""".formatted(ROOM_ID)));
			assertSqlState("23514", () -> statement.execute("""
					INSERT INTO tbl_studio_room_photo (id, room_id, media_asset_id, order_index)
					VALUES ('00000000-0000-0000-0000-000000000912', '%s',
					        '00000000-0000-0000-0000-000000000901', 10)
					""".formatted(ROOM_ID)));
			assertSqlState("23514", () -> statement.execute("""
					INSERT INTO tbl_studio_equipment_feature (id, equipment_id, position, label)
					VALUES ('00000000-0000-0000-0000-000000000913', '%s', 12, 'Thirteenth feature')
					""".formatted(EQUIPMENT_ID)));
			assertSqlState("23514", () -> statement.execute("""
					INSERT INTO tbl_studio_equipment_photo (id, equipment_id, position, media_asset_id)
					VALUES ('00000000-0000-0000-0000-000000000914', '%s', 5,
					        '00000000-0000-0000-0000-000000000901')
					""".formatted(EQUIPMENT_ID)));
			assertSqlState("23505", () -> statement.execute("""
					INSERT INTO tbl_studio_room
					    (id, studio_profile_id, client_request_id, creation_payload_hash,
					     slot_index, name, capacity)
					VALUES
					    ('00000000-0000-0000-0000-000000000302', '%s',
					     '00000000-0000-0000-0000-000000000612', repeat('e', 64),
					     0, 'Duplicate active slot', 2)
					""".formatted(STUDIO_ID)));
		}
	}

	@Test
	void creationKeysAndCategoryReviewRemainSafeAcrossRetriesAndFlushOrder() throws Exception {
		executeMigration(migrationSql());
		seedStudioAndRoom();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertSqlState("23505", () -> statement.execute("""
					INSERT INTO tbl_studio_room
					    (id, studio_profile_id, client_request_id, creation_payload_hash,
					     slot_index, name, capacity)
					VALUES
					    ('00000000-0000-0000-0000-000000000302', '%s',
					     '00000000-0000-0000-0000-000000000611', repeat('f', 64),
					     1, 'Retried room', 4)
					""".formatted(STUDIO_ID)));

			statement.execute("""
					INSERT INTO tbl_studio_room_occupancy
					    (id, room_id, client_request_id, type, starts_at, ends_at, active, created_by)
					VALUES
					    ('00000000-0000-0000-0000-000000000721', '%s',
					     '00000000-0000-0000-0000-000000000631', 'MANUAL_BLOCK',
					     TIMESTAMPTZ '2026-08-02 15:00:00+03', TIMESTAMPTZ '2026-08-02 16:00:00+03',
					     true, '%s')
					""".formatted(ROOM_ID, OWNER_ID));
			assertSqlState("23505", () -> statement.execute("""
					INSERT INTO tbl_studio_room_occupancy
					    (id, room_id, client_request_id, type, starts_at, ends_at, active, created_by)
					VALUES
					    ('00000000-0000-0000-0000-000000000722', '%s',
					     '00000000-0000-0000-0000-000000000631', 'MANUAL_BLOCK',
					     TIMESTAMPTZ '2026-08-02 16:00:00+03', TIMESTAMPTZ '2026-08-02 17:00:00+03',
					     true, '%s')
					""".formatted(ROOM_ID, OWNER_ID)));

			statement.execute("""
					INSERT INTO tbl_studio_equipment
					    (id, studio_profile_id, creation_client_request_id, creation_payload_hash,
					     category_id, name, total_quantity)
					VALUES ('%s', '%s', '00000000-0000-0000-0000-000000000641',
					        repeat('c', 64), '%s', 'Shure SM58', 1)
					""".formatted(EQUIPMENT_ID, STUDIO_ID, DYNAMIC_MIC_CATEGORY_ID));
			assertSqlState("23505", () -> statement.execute("""
					INSERT INTO tbl_studio_equipment
					    (id, studio_profile_id, creation_client_request_id, creation_payload_hash,
					     category_id, name, total_quantity)
					VALUES ('00000000-0000-0000-0000-000000000402', '%s',
					        '00000000-0000-0000-0000-000000000641', repeat('c', 64),
					        '%s', 'Retried microphone', 1)
					""".formatted(STUDIO_ID, DYNAMIC_MIC_CATEGORY_ID)));

			statement.execute("""
					INSERT INTO tbl_backline_category_request
					    (id, studio_profile_id, requested_by_user_id, client_request_id,
					     request_payload_hash, request_type, requested_name,
					     normalized_requested_name)
					VALUES
					    ('00000000-0000-0000-0000-000000000a01', '%s', '%s',
					     '00000000-0000-0000-0000-000000000a02', repeat('d', 64),
					     'ROOT_CATEGORY', 'Test Root', 'test root')
					""".formatted(STUDIO_ID, OWNER_ID));
			statement.execute("""
					INSERT INTO tbl_backline_category_request_child
					    (id, request_id, position, name, normalized_name)
					VALUES
					    ('00000000-0000-0000-0000-000000000a03',
					     '00000000-0000-0000-0000-000000000a01', 0, 'Test Child', 'test child')
					""");

			// Hibernate may flush the request status before resolving its child rows.
			statement.execute("""
					UPDATE tbl_backline_category_request
					   SET status = 'APPROVED', reviewed_by_user_id = '%s', reviewed_at = CURRENT_TIMESTAMP,
					       resolved_root_category_id = '10000000-0000-0000-0000-000000000009',
					       resolved_category_id = '%s'
					 WHERE id = '00000000-0000-0000-0000-000000000a01'
					""".formatted(OWNER_ID, DYNAMIC_MIC_CATEGORY_ID));
			statement.execute("""
					UPDATE tbl_backline_category_request_child
					   SET resolved_category_id = '%s'
					 WHERE id = '00000000-0000-0000-0000-000000000a03'
					""".formatted(DYNAMIC_MIC_CATEGORY_ID));

			assertSqlState("23514", () -> statement.execute("""
					INSERT INTO tbl_backline_category_request_child
					    (id, request_id, position, name, normalized_name)
					VALUES
					    ('00000000-0000-0000-0000-000000000a04',
					     '00000000-0000-0000-0000-000000000a01', 1, 'Late Child', 'late child')
					"""));
			assertSqlState("23505", () -> statement.execute("""
					INSERT INTO tbl_backline_category_request
					    (id, studio_profile_id, requested_by_user_id, client_request_id,
					     request_payload_hash, request_type, requested_name,
					     normalized_requested_name)
					VALUES
					    ('00000000-0000-0000-0000-000000000a05', '%s', '%s',
					     '00000000-0000-0000-0000-000000000a02', repeat('d', 64),
					     'ROOT_CATEGORY', 'Retried Root', 'retried root')
					""".formatted(STUDIO_ID, OWNER_ID)));
			assertSqlState("23514", () -> statement.execute("""
					INSERT INTO tbl_studio_room_reservation
					    (id, room_id, requester_id, starts_at, ends_at, status,
					     approval_required_snapshot, total_price_minor_snapshot,
					     currency_snapshot, client_request_id)
					VALUES
					    ('00000000-0000-0000-0000-000000000a06', '%s', '%s',
					     TIMESTAMPTZ '2026-08-03 10:00:00+03', TIMESTAMPTZ '2026-08-03 11:00:00+03',
					     'CONFIRMED', false, 0, 'TRY',
					     '00000000-0000-0000-0000-000000000a07')
					""".formatted(ROOM_ID, REQUESTER_ID)));
		}
	}

	@Test
	void lateFailureRollsBackAllStudioSchemaAndBackfills() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO tbl_user (id) VALUES ('" + OWNER_ID + "')");
			statement.execute("INSERT INTO tbl_studio_profile (id, user_id) VALUES ('"
					+ STUDIO_ID + "', '" + OWNER_ID + "')");
		}

		String failingMigration = migrationSql().replaceFirst(
				"(?s)\\RCOMMIT;\\s*$",
				"\nDO \\$\\$ BEGIN RAISE EXCEPTION 'forced studio migration failure'; END \\$\\$;\nCOMMIT;"
		);

		assertThatThrownBy(() -> executeMigration(failingMigration))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("forced studio migration failure");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, """
					SELECT count(*) FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND table_name = 'tbl_user'
					   AND column_name = 'public_code'
					""")).isZero();
			assertThat(singleInt(statement, """
					SELECT count(*) FROM information_schema.tables
					 WHERE table_schema = current_schema()
					   AND table_name = 'tbl_studio_room'
					""")).isZero();
			assertThat(singleInt(statement,
					"SELECT count(*) FROM tbl_permissions WHERE name = 'MANAGE_BACKLINE_CATALOG'"))
					.isZero();
		}
	}

	private void seedStudioAndRoom() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO tbl_user (id) VALUES ('" + OWNER_ID + "'), ('"
					+ REQUESTER_ID + "')");
			statement.execute("INSERT INTO tbl_studio_profile (id, user_id) VALUES ('"
					+ STUDIO_ID + "', '" + OWNER_ID + "')");
			statement.execute("""
					INSERT INTO tbl_studio_room
					    (id, studio_profile_id, client_request_id, creation_payload_hash,
					     slot_index, name, capacity,
					     hourly_price_minor, currency, reservation_approval_required)
					VALUES ('%s', '%s', '00000000-0000-0000-0000-000000000611',
					        repeat('a', 64), 0, 'Prova Odası A', 6, 75000, 'TRY', true)
					""".formatted(ROOM_ID, STUDIO_ID));
		}
	}

	private static void assertSqlState(String expected, SqlAction action) {
		assertThatThrownBy(action::run)
				.isInstanceOf(SQLException.class)
				.satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(expected));
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(),
				POSTGRES.getPassword()
		);
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(
				System.getProperty("user.dir"),
				"scripts", "db", "2026-07-21-studio-domain.sql"
		));
	}

	private static void executeMigration(String sql) throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static int singleInt(Statement statement, String sql) throws SQLException {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getInt(1);
		}
	}

	private static String singleString(Statement statement, String sql) throws SQLException {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getString(1);
		}
	}

	private static void assertEntityColumns(Connection connection, Class<?>... entityTypes)
			throws SQLException {
		for (Class<?> entityType : entityTypes) {
			Table table = entityType.getAnnotation(Table.class);
			assertThat(table)
					.as("@Table on %s", entityType.getName())
					.isNotNull();

			Set<String> mappedColumns = mappedColumns(entityType);
			Set<String> databaseColumns = new TreeSet<>();
			try (var statement = connection.prepareStatement("""
					SELECT column_name
					  FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND table_name = ?
					 ORDER BY column_name
					""")) {
				statement.setString(1, table.name());
				try (ResultSet result = statement.executeQuery()) {
					while (result.next()) {
						databaseColumns.add(result.getString(1));
					}
				}
			}

			assertThat(databaseColumns)
					.as("database columns for %s", table.name())
					.containsExactlyElementsOf(mappedColumns);
		}
	}

	private static Set<String> mappedColumns(Class<?> entityType) {
		Set<String> columns = new TreeSet<>();
		for (Class<?> type = entityType; type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers())
						|| field.isAnnotationPresent(Transient.class)
						|| field.isAnnotationPresent(OneToMany.class)
						|| field.isAnnotationPresent(ManyToMany.class)
						|| field.isAnnotationPresent(ElementCollection.class)) {
					continue;
				}

				JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
				if (joinColumn != null) {
					columns.add(joinColumn.name());
					continue;
				}

				Column column = field.getAnnotation(Column.class);
				String configuredName = column == null ? "" : column.name();
				columns.add(configuredName.isBlank() ? snakeCase(field.getName()) : configuredName);
			}
		}
		return columns;
	}

	private static String snakeCase(String value) {
		return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
	}

	@FunctionalInterface
	private interface SqlAction {
		void run() throws SQLException;
	}
}
