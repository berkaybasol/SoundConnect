package com.berkayb.soundconnect.modules.tablegroup.migration;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.file.*;
import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TableGroupHardeningMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_table_group")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void resetSchema() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
			statement.execute("CREATE TABLE tbl_city (id uuid PRIMARY KEY)");
			statement.execute("CREATE TABLE tbl_district (id uuid PRIMARY KEY, city_id uuid NOT NULL)");
			statement.execute("CREATE TABLE tbl_neighborhood (id uuid PRIMARY KEY, district_id uuid NOT NULL)");
			statement.execute("""
					CREATE TABLE tbl_notification (
					  id uuid PRIMARY KEY,
					  recipient_id uuid,
					  created_at timestamp without time zone
					)
					""");
		}
	}

	@Test
	void freshMigrationIsRerunnableAndCreatesProductionConstraints() throws Exception {
		execute(migrationSql());
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, """
					SELECT count(*) FROM information_schema.tables
					 WHERE table_schema = current_schema()
					   AND table_name IN (
					     'tbl_table_group','tbl_table_group_participants',
					     'tbl_table_group_message','tbl_table_group_notification_outbox'
					   )
					""")).isEqualTo(4);
			assertThat(singleInt(statement, """
					SELECT count(*) FROM information_schema.columns
					 WHERE table_name = 'tbl_table_group'
					   AND column_name IN ('expires_at','start_at')
					   AND data_type = 'timestamp with time zone'
					""")).isEqualTo(2);
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_indexes
					 WHERE schemaname = current_schema()
					   AND indexname IN (
					     'uk_table_group_participant_user',
					     'idx_tg_participant_user_status_group',
					     'idx_tablegroup_owner_status_exp_id',
					     'idx_tg_msg_group_created_desc_id',
					     'idx_tg_notification_outbox_due',
					     'uk_notification_source_event_id'
					   )
					""")).isEqualTo(6);
		}
	}

	@Test
	void ownerLifecycleLookupUsesDedicatedIndexAtProductionCardinality() throws Exception {
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO tbl_city VALUES ('00000000-0000-0000-0000-000000000001')");
			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					)
					SELECT
					 md5('group-' || series)::uuid,
					 CASE WHEN series = 1
					      THEN '10000000-0000-0000-0000-000000000001'::uuid
					      ELSE md5('owner-' || series)::uuid END,
					 'Venue ' || series, 2, 20, 40,
					 CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '1 hour', 'ACTIVE',
					 '00000000-0000-0000-0000-000000000001'::uuid
					FROM generate_series(1, 5000) AS series
					""");
			statement.execute("ANALYZE tbl_table_group");

			assertThat(explain(statement, """
					SELECT id
					  FROM tbl_table_group
					 WHERE owner_id = '10000000-0000-0000-0000-000000000001'::uuid
					   AND status = 'ACTIVE'
					   AND expires_at > CURRENT_TIMESTAMP
					"""))
					.contains("idx_tablegroup_owner_status_exp_id");
		}
	}

	@Test
	void rerunReplacesLegacyVenueShapeAndAcceptsRegisteredSnapshotsAndNoVenue() throws Exception {
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO tbl_city VALUES ('00000000-0000-0000-0000-000000000001')");
			statement.execute("ALTER TABLE tbl_table_group DROP CONSTRAINT ck_table_group_venue_shape");
			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES
					 (
					  '10000000-0000-0000-0000-000000000001',
					  '20000000-0000-0000-0000-000000000001',
					  '30000000-0000-0000-0000-000000000001','Registered Venue',2,20,40,
					  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					  '00000000-0000-0000-0000-000000000001'
					 ),
					 (
					  '10000000-0000-0000-0000-000000000002',
					  '20000000-0000-0000-0000-000000000002',
					  '30000000-0000-0000-0000-000000000002',E' \\t\\n\\r\\f\\013 ',2,20,40,
					  CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					  '00000000-0000-0000-0000-000000000001'
					 )
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_gender_prefs (table_group_id,gender_pref) VALUES
					 ('10000000-0000-0000-0000-000000000001','MALE'),
					 ('10000000-0000-0000-0000-000000000001','FEMALE'),
					 ('10000000-0000-0000-0000-000000000002','MALE'),
					 ('10000000-0000-0000-0000-000000000002','FEMALE')
					""");
			statement.execute("""
					ALTER TABLE tbl_table_group
					ADD CONSTRAINT ck_table_group_venue_shape
					CHECK (
					 (venue_id IS NOT NULL AND (venue_name IS NULL OR btrim(venue_name) = ''))
					 OR
					 (venue_id IS NULL AND venue_name IS NOT NULL
					  AND char_length(btrim(venue_name)) BETWEEN 1 AND 64)
					) NOT VALID
					""");
		}

		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT venue_name FROM tbl_table_group
					 WHERE id = '10000000-0000-0000-0000-000000000001'
					""")).isEqualTo("Registered Venue");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM tbl_table_group
					 WHERE id = '10000000-0000-0000-0000-000000000002'
					   AND venue_name IS NULL
					""")).isEqualTo(1);
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group'::regclass
					   AND conname = 'ck_table_group_venue_shape'
					   AND convalidated
					""")).isEqualTo(1);

			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000003',
					 '20000000-0000-0000-0000-000000000003',
					 '30000000-0000-0000-0000-000000000003','Another Snapshot',2,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					""");

			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000009',
					 '20000000-0000-0000-0000-000000000009',
					 NULL,NULL,2,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					""");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM tbl_table_group
					 WHERE id = '10000000-0000-0000-0000-000000000009'
					   AND venue_id IS NULL
					   AND venue_name IS NULL
					""")).isEqualTo(1);

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000004',
					 '20000000-0000-0000-0000-000000000004',E' \\t\\n\\r\\f\\013 ',2,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_venue_shape");

			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000005',
					 '20000000-0000-0000-0000-000000000005',
					 '30000000-0000-0000-0000-000000000005',
					 E' \\t' || repeat('r',64) || E'\\n ',2,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					""");

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000006',
					 '20000000-0000-0000-0000-000000000006',
					 '30000000-0000-0000-0000-000000000006',
					 repeat('r',65),2,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_venue_shape");

			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000007',
					 '20000000-0000-0000-0000-000000000007',
					 repeat('m',64),2,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					""");

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000008',
					 '20000000-0000-0000-0000-000000000008',
					 repeat('m',65),2,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_venue_shape");
		}
	}

	@Test
	void legacyLocalDateTimesAreConvertedUsingTheirActualSourceClocks() throws Exception {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO tbl_city VALUES ('00000000-0000-0000-0000-000000000001')");
			statement.execute("""
					CREATE TABLE tbl_table_group (
					 id uuid PRIMARY KEY, created_at timestamp without time zone,
					 updated_at timestamp without time zone, owner_id uuid NOT NULL,
					 venue_name varchar(128), venue_id uuid, max_person_count int NOT NULL,
					 age_min int NOT NULL, age_max int NOT NULL, start_at timestamp without time zone,
					 expires_at timestamp without time zone NOT NULL, status varchar(16) NOT NULL,
					 city_id uuid NOT NULL, district_id uuid, neighborhood_id uuid
					)
					""");
			statement.execute("CREATE TABLE tbl_table_group_gender_prefs (table_group_id uuid, gender_pref varchar(16))");
			statement.execute("""
					CREATE TABLE tbl_table_group_participants (
					 table_group_id uuid, user_id uuid, joined_at timestamp without time zone,
					 status varchar(16), join_note varchar(256)
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_table_group_message (
					 id uuid PRIMARY KEY, created_at timestamp without time zone,
					 updated_at timestamp without time zone, table_group_id uuid NOT NULL,
					 sender_id uuid NOT NULL, content text NOT NULL,
					 message_type varchar(32) NOT NULL, deleted_at timestamp without time zone
					)
					""");
			statement.execute("""
					CREATE INDEX idx_tg_msg_group_created
					ON tbl_table_group_message (table_group_id, created_at)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,created_at,owner_id,venue_name,max_person_count,age_min,age_max,
					 expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000001', TIMESTAMP '2026-08-17 19:00:00',
					 '20000000-0000-0000-0000-000000000001','Venue',2,22,35,
					 TIMESTAMP '2026-08-17 23:00:00','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_participants (table_group_id,user_id,joined_at,status)
					VALUES (
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001',
					 TIMESTAMP '2026-08-17 20:00:00', NULL
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_gender_prefs (table_group_id,gender_pref)
					VALUES
					 ('10000000-0000-0000-0000-000000000001','MALE'),
					 ('10000000-0000-0000-0000-000000000001','FEMALE')
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_message (
					 id,table_group_id,sender_id,content,message_type
					) VALUES (
					 '30000000-0000-0000-0000-000000000001',
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','hello','TEXT'
					)
					""");
		}

		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleString(statement, """
					SELECT expires_at AT TIME ZONE 'UTC' FROM tbl_table_group
					 WHERE id='10000000-0000-0000-0000-000000000001'
					""")).startsWith("2026-08-17 20:00:00");
			assertThat(singleString(statement, """
					SELECT (joined_at AT TIME ZONE 'UTC')::text || ':' || status
					  FROM tbl_table_group_participants
					""")).isEqualTo("2026-08-17 20:00:00:ACCEPTED");
			assertThat(singleString(statement, """
					SELECT indexdef FROM pg_indexes
					 WHERE schemaname = current_schema()
					   AND indexname = 'idx_tg_msg_group_created_desc_id'
					""")).contains("table_group_id", "created_at DESC", "id DESC", "deleted_at IS NULL");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_indexes
					 WHERE schemaname = current_schema()
					   AND indexname = 'idx_tg_msg_group_created'
					""")).isZero();
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group_message'::regclass
					   AND conname = 'fk_table_group_message_group'
					   AND convalidated
					""")).isEqualTo(1);
			assertThat(singleInt(statement, """
					SELECT count(*) FROM tbl_table_group_message
					 WHERE created_at IS NOT NULL
					""")).isEqualTo(1);
			assertThat(singleString(statement, """
					SELECT is_nullable FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND table_name = 'tbl_table_group_message'
					   AND column_name = 'created_at'
					""")).isEqualTo("NO");
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND is_nullable = 'NO'
					   AND (
					     (table_name = 'tbl_table_group' AND column_name IN (
					       'owner_id','max_person_count','age_min','age_max','expires_at','status','city_id'
					     ))
					     OR (table_name = 'tbl_table_group_participants' AND column_name IN (
					       'table_group_id','user_id','joined_at','status'
					     ))
					     OR (table_name = 'tbl_table_group_gender_prefs' AND column_name IN (
					       'table_group_id','gender_pref'
					     ))
					     OR (table_name = 'tbl_table_group_message' AND column_name IN (
					       'table_group_id','sender_id','content','message_type','created_at'
					     ))
					   )
					""")).isEqualTo(18);
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					 WHERE convalidated
					   AND conname IN (
					     'fk_table_group_city','fk_table_group_district',
					     'fk_table_group_neighborhood','fk_table_group_participant_group',
					     'fk_table_group_gender_group','fk_table_group_message_group'
					   )
					""")).isEqualTo(6);
		}
	}

	@Test
	void rerunRejectsLegacyLocationHierarchyMismatch() throws Exception {
		execute(migrationSql());
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tbl_city VALUES
					 ('00000000-0000-0000-0000-000000000001'),
					 ('00000000-0000-0000-0000-000000000002')
					""");
			statement.execute("""
					INSERT INTO tbl_district (id,city_id) VALUES
					 ('01000000-0000-0000-0000-000000000001',
					  '00000000-0000-0000-0000-000000000002')
					""");
			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id,district_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','Venue',2,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					 '00000000-0000-0000-0000-000000000001',
					 '01000000-0000-0000-0000-000000000001'
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_participants
					 (table_group_id,user_id,joined_at,status) VALUES (
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001',CURRENT_TIMESTAMP,'ACCEPTED'
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_gender_prefs VALUES
					 ('10000000-0000-0000-0000-000000000001','MALE'),
					 ('10000000-0000-0000-0000-000000000001','FEMALE')
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("inconsistent group location hierarchy");
	}

	@Test
	void rerunRejectsRowsWhoseLifetimeExceedsTwentyFourHours() throws Exception {
		execute(migrationSql());
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE tbl_table_group DROP CONSTRAINT ck_table_group_lifetime");
			statement.execute("INSERT INTO tbl_city VALUES ('00000000-0000-0000-0000-000000000001')");
			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','Venue',2,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '25 hours','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("at most 24 hours");
	}

	@Test
	void rerunRejectsLegacyGroupsAboveTheLiveMessageCap() throws Exception {
		execute(migrationSql());
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO tbl_city VALUES ('00000000-0000-0000-0000-000000000001')");
			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','Venue',2,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_participants
					 (table_group_id,user_id,joined_at,status) VALUES (
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001',CURRENT_TIMESTAMP,'ACCEPTED'
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_gender_prefs VALUES
					 ('10000000-0000-0000-0000-000000000001','MALE'),
					 ('10000000-0000-0000-0000-000000000001','FEMALE')
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_message (
					 id,created_at,table_group_id,sender_id,content,message_type
					)
					SELECT md5(sequence_number::text)::uuid,
					       CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
					       '10000000-0000-0000-0000-000000000001',
					       '20000000-0000-0000-0000-000000000001',
					       'message','TEXT'
					  FROM generate_series(1,10001) AS generated(sequence_number)
					""");
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("10000-message aggregate cap");
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(System.getProperty("user.dir"), "scripts", "db",
				"2026-08-17-tablegroup-hardening.sql"));
	}

	private static void execute(String sql) throws SQLException {
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

	private static String explain(Statement statement, String sql) throws SQLException {
		StringBuilder plan = new StringBuilder();
		try (ResultSet result = statement.executeQuery("EXPLAIN (COSTS OFF) " + sql)) {
			while (result.next()) {
				plan.append(result.getString(1)).append('\n');
			}
		}
		return plan.toString();
	}

	private static String singleString(Statement statement, String sql) throws SQLException {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getString(1);
		}
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}
}
