package com.berkayb.soundconnect.modules.tablegroup.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TableGroupCreateContractStrictMigrationPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_table_group_create_contract")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void resetSchema() throws SQLException {
		resetDatabase();
	}

	@Test
	void migrationPreservesTerminalNullDescriptionsAndCanonicalizesHibernateConstraint() throws Exception {
		createPredecessorSchema(UniqueShape.HIBERNATE_CONSTRAINT);
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertGroup(statement, 1, "ACTIVE", "Canlı müzik ve yeni insanlarla tanışma", true, true);
			insertGroup(statement, 2, "INACTIVE", null, true, true);
			insertGroup(statement, 3, "CANCELLED", null, true, true);
		}

		execute(migrationSql());
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_table_group WHERE description IS NULL"))
					.isEqualTo(2);
			assertThat(columnNullability(statement, "description")).isEqualTo("YES");
			assertThat(columnNullability(statement, "meeting_at")).isEqualTo("NO");
			assertThat(columnNullability(statement, "create_request_key")).isEqualTo("NO");
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group'::regclass
					   AND conname = 'ck_table_group_create_required_fields'
					   AND NOT convalidated
					""")).isEqualTo(1);
			assertCanonicalFullUnique(statement);

			insertGroup(statement, 4, "ACTIVE", "Yeni ve geçerli masa", true, true);
			assertThatThrownBy(() -> insertGroup(
					statement, 5, "CANCELLED", null, true, true))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_create_required_fields");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,create_request_key,description,start_at,meeting_at,expires_at,status
					) VALUES (
					 '10000000-0000-0000-0000-000000000006',
					 '20000000-0000-0000-0000-000000000004',
					 '30000000-0000-0000-0000-000000000004',
					 'Duplicate create key',
					 '2026-09-02 10:00:00+00','2026-09-02 12:00:00+00',
					 '2026-09-03 10:00:00+00','ACTIVE'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("uk_table_group_owner_create_request");
		}
	}

	@Test
	void cleanPartialIndexSchemaConvergesEveryRequiredColumnAndIsRerunnable() throws Exception {
		createPredecessorSchema(UniqueShape.HARDENING_PARTIAL_INDEX);
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertGroup(statement, 1, "ACTIVE", "İlk prod için eksiksiz masa", true, true);
		}

		execute(migrationSql());
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(columnNullability(statement, "description")).isEqualTo("NO");
			assertThat(columnNullability(statement, "meeting_at")).isEqualTo("NO");
			assertThat(columnNullability(statement, "create_request_key")).isEqualTo("NO");
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group'::regclass
					   AND conname = 'ck_table_group_create_required_fields'
					   AND convalidated
					""")).isEqualTo(1);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group_message'::regclass
					   AND conname = 'ck_table_group_text_client_message_id'
					   AND convalidated
					""")).isEqualTo(1);
			assertCanonicalFullUnique(statement);

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,create_request_key,description,start_at,meeting_at,expires_at,status
					) VALUES (
					 '10000000-0000-0000-0000-000000000007',
					 '20000000-0000-0000-0000-000000000007',
					 '30000000-0000-0000-0000-000000000007',
					 NULL,
					 '2026-09-02 10:00:00+00','2026-09-02 12:00:00+00',
					 '2026-09-03 10:00:00+00','ACTIVE'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("description");
		}
	}

	@Test
	void textMessageFencePreservesLegacyRowsAndRequiresKeysForNewOrUpdatedText() throws Exception {
		createPredecessorSchema(UniqueShape.HIBERNATE_CONSTRAINT);
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertGroup(statement, 1, "ACTIVE", "Mesaj sözleşmesi masası", true, true);
			statement.execute("""
					INSERT INTO tbl_table_group_message (
					 id,table_group_id,sender_id,client_message_id,message_type
					) VALUES (
					 '40000000-0000-0000-0000-000000000001',
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001',
					 NULL,'TEXT'
					)
					""");
		}

		execute(migrationSql());
		execute(migrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, """
					SELECT count(*) FROM tbl_table_group_message
					 WHERE message_type='TEXT' AND client_message_id IS NULL
					""")).isEqualTo(1);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group_message'::regclass
					   AND conname = 'ck_table_group_text_client_message_id'
					   AND NOT convalidated
					""")).isEqualTo(1);

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_message (
					 id,table_group_id,sender_id,client_message_id,message_type
					) VALUES (
					 '40000000-0000-0000-0000-000000000002',
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001',
					 NULL,'TEXT'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_text_client_message_id");

			assertThatThrownBy(() -> statement.execute("""
					UPDATE tbl_table_group_message
					   SET sender_id='20000000-0000-0000-0000-000000000002'
					 WHERE id='40000000-0000-0000-0000-000000000001'
					"""))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ck_table_group_text_client_message_id");

			statement.execute("""
					INSERT INTO tbl_table_group_message (
					 id,table_group_id,sender_id,client_message_id,message_type
					) VALUES (
					 '40000000-0000-0000-0000-000000000003',
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001',
					 NULL,'GAME'
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_message (
					 id,table_group_id,sender_id,client_message_id,message_type
					) VALUES (
					 '40000000-0000-0000-0000-000000000004',
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001',
					 '50000000-0000-0000-0000-000000000004','TEXT'
					)
					""");
			assertThat(singleInt(statement,
					"SELECT count(*) FROM tbl_table_group_message WHERE id IN (" +
							"'40000000-0000-0000-0000-000000000003'," +
							"'40000000-0000-0000-0000-000000000004')"))
					.isEqualTo(2);
		}
	}

	@Test
	void migrationRejectsNonTerminalNullDescriptionWithoutPartialChanges() throws Exception {
		createPredecessorSchema(UniqueShape.HARDENING_PARTIAL_INDEX);
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertGroup(statement, 1, "ACTIVE", null, true, true);
		}

		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("non-terminal rows without description");

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(columnNullability(statement, "meeting_at")).isEqualTo("YES");
			assertThat(columnNullability(statement, "create_request_key")).isEqualTo("YES");
			assertThat(singleInt(statement, """
					SELECT count(*) FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group'::regclass
					   AND conname = 'ck_table_group_create_required_fields'
					""")).isZero();
			assertThat(singleString(statement, """
					SELECT indexdef FROM pg_indexes
					 WHERE schemaname = current_schema()
					   AND tablename = 'tbl_table_group'
					   AND indexname = 'uk_table_group_owner_create_request'
					""")).contains("WHERE (create_request_key IS NOT NULL)");
		}
	}

	@Test
	void migrationRejectsMissingMeetingKeyAndDuplicatePairs() throws Exception {
		createPredecessorSchema(UniqueShape.HARDENING_PARTIAL_INDEX);
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertGroup(statement, 1, "ACTIVE", "Eksik buluşma zamanı", false, true);
		}
		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("without meeting_at");

		resetDatabase();
		createPredecessorSchema(UniqueShape.HARDENING_PARTIAL_INDEX);
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertGroup(statement, 1, "ACTIVE", "Eksik istek anahtarı", true, false);
		}
		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("without create_request_key");

		resetDatabase();
		createPredecessorSchema(UniqueShape.NONE);
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertGroup(statement, 1, "ACTIVE", "Birinci istek", true, true);
			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,create_request_key,description,start_at,meeting_at,expires_at,status
					) VALUES (
					 '10000000-0000-0000-0000-000000000002',
					 '20000000-0000-0000-0000-000000000001',
					 '30000000-0000-0000-0000-000000000001',
					 'İkinci istek',
					 '2026-09-02 10:00:00+00','2026-09-02 12:00:00+00',
					 '2026-09-03 10:00:00+00','ACTIVE'
					)
					""");
		}
		assertThatThrownBy(() -> execute(migrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("duplicate owner/create_request_key pairs");
	}

	private static void createPredecessorSchema(UniqueShape uniqueShape) throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE tbl_table_group (
					 id uuid PRIMARY KEY,
					 owner_id uuid NOT NULL,
					 create_request_key uuid,
					 description varchar(280),
					 start_at timestamp with time zone NOT NULL,
					 meeting_at timestamp with time zone,
					 expires_at timestamp with time zone NOT NULL,
					 status varchar(16) NOT NULL,
					 CONSTRAINT ck_table_group_description CHECK (
					  description IS NULL OR (
					   description = btrim(description)
					   AND char_length(description) BETWEEN 1 AND 280
					  )
					 ),
					 CONSTRAINT ck_table_group_meeting_time CHECK (
					  meeting_at IS NULL OR (
					   meeting_at > start_at
					   AND meeting_at <= expires_at
					   AND meeting_at <= start_at + INTERVAL '24 hours'
					  )
					 )
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_table_group_message (
					 id uuid PRIMARY KEY,
					 table_group_id uuid NOT NULL,
					 sender_id uuid NOT NULL,
					 client_message_id uuid,
					 message_type varchar(32) NOT NULL
					)
					""");

			if (uniqueShape == UniqueShape.HIBERNATE_CONSTRAINT) {
				statement.execute("""
						ALTER TABLE tbl_table_group
						 ADD CONSTRAINT uk_table_group_owner_create_request
						 UNIQUE (owner_id, create_request_key)
						""");
			} else if (uniqueShape == UniqueShape.HARDENING_PARTIAL_INDEX) {
				statement.execute("""
						CREATE UNIQUE INDEX uk_table_group_owner_create_request
						 ON tbl_table_group (owner_id, create_request_key)
						 WHERE create_request_key IS NOT NULL
						""");
			}
		}
	}

	private static void insertGroup(
			Statement statement,
			int suffix,
			String status,
			String description,
			boolean withMeeting,
			boolean withCreateKey
	) throws SQLException {
		String sql = """
				INSERT INTO tbl_table_group (
				 id,owner_id,create_request_key,description,start_at,meeting_at,expires_at,status
				) VALUES (
				 '%s','%s',%s,%s,
				 '2026-09-02 10:00:00+00',%s,'2026-09-03 10:00:00+00','%s'
				)
				""".formatted(
				"10000000-0000-0000-0000-%012d".formatted(suffix),
				"20000000-0000-0000-0000-%012d".formatted(suffix),
				withCreateKey
						? "'" + "30000000-0000-0000-0000-%012d".formatted(suffix) + "'"
						: "NULL",
				description == null ? "NULL" : "'" + description.replace("'", "''") + "'",
				withMeeting ? "'2026-09-02 12:00:00+00'" : "NULL",
				status
		);
		statement.execute(sql);
	}

	private static void assertCanonicalFullUnique(Statement statement) throws SQLException {
		assertThat(singleString(statement, """
				SELECT contype::text || ':' || pg_get_constraintdef(oid, true)
				  FROM pg_constraint
				 WHERE conrelid = 'tbl_table_group'::regclass
				   AND conname = 'uk_table_group_owner_create_request'
				"""))
				.isEqualTo("u:UNIQUE (owner_id, create_request_key)");
		assertThat(singleString(statement, """
				SELECT indexdef
				  FROM pg_indexes
				 WHERE schemaname = current_schema()
				   AND tablename = 'tbl_table_group'
				   AND indexname = 'uk_table_group_owner_create_request'
				"""))
				.doesNotContain(" WHERE ");
	}

	private static String columnNullability(Statement statement, String column) throws SQLException {
		return singleString(statement, """
				SELECT is_nullable
				  FROM information_schema.columns
				 WHERE table_schema = current_schema()
				   AND table_name = 'tbl_table_group'
				   AND column_name = '%s'
				""".formatted(column));
	}

	private static String migrationSql() throws Exception {
		return Files.readString(Path.of(System.getProperty("user.dir"), "scripts", "db",
				"2026-09-02-tablegroup-create-contract-strict.sql"));
	}

	private static void execute(String sql) throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static void resetDatabase() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public");
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

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
	}

	private enum UniqueShape {
		HIBERNATE_CONSTRAINT,
		HARDENING_PARTIAL_INDEX,
		NONE
	}
}
