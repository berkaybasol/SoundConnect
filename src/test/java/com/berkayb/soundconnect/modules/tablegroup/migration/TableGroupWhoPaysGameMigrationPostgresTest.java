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
class TableGroupWhoPaysGameMigrationPostgresTest {

	private static final String CITY_ID = "00000000-0000-0000-0000-000000000001";
	private static final String GROUP_ID = "10000000-0000-0000-0000-000000000001";
	private static final String OWNER_ID = "20000000-0000-0000-0000-000000000001";
	private static final String GUEST_ID = "20000000-0000-0000-0000-000000000002";
	private static final String OTHER_GAME_PLAYER_ID = "20000000-0000-0000-0000-000000000003";
	private static final String GAME_ID = "30000000-0000-0000-0000-000000000001";

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("soundconnect_table_group_game")
			.withUsername("soundconnect")
			.withPassword("soundconnect");

	@BeforeEach
	void resetSchema() throws Exception {
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
		execute(hardeningMigrationSql());
	}

	@Test
	void freshMigrationIsRerunnableAndCreatesTheGameContract() throws Exception {
		execute(gameMigrationSql());
		execute(gameMigrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM information_schema.tables
					 WHERE table_schema = current_schema()
					   AND table_name IN (
					     'tbl_table_group_game',
					     'tbl_table_group_game_player',
					     'tbl_table_group_game_action'
					   )
					""")).isEqualTo(3);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND data_type = 'timestamp with time zone'
					   AND (
					     (table_name = 'tbl_table_group_game'
					       AND column_name IN ('join_deadline_at','action_deadline_at','completed_at'))
					     OR (table_name = 'tbl_table_group_game_player' AND column_name = 'joined_at')
					   )
					""")).isEqualTo(4);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM information_schema.columns
					 WHERE table_schema = current_schema()
					   AND table_name = 'tbl_table_group_game'
					   AND column_name IN ('version','revision')
					   AND is_nullable = 'NO'
					""")).isEqualTo(2);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_indexes
					 WHERE schemaname = current_schema()
					   AND indexname IN (
					     'uk_tg_game_one_active',
					     'idx_tg_game_due',
					     'idx_tg_game_table_status',
					     'idx_tg_game_player_status',
					     'idx_tg_game_action_round',
					     'idx_tg_game_action_revealed',
					     'uk_tg_message_game'
					   )
					""")).isEqualTo(7);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE convalidated
					   AND conname IN (
					     'fk_tg_game_group',
					     'fk_tg_game_player_game',
					     'fk_tg_game_action_game',
					     'fk_tg_game_action_actor',
					     'fk_tg_game_action_target',
					     'fk_tg_message_game',
					     'ck_tg_game_revision',
					     'ck_tg_game_state_shape',
					     'ck_tg_game_action_shape',
					     'ck_tg_message_game_shape'
					   )
					""")).isEqualTo(10);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conname IN (
					   'uk_tg_game_create_request',
					   'uk_tg_game_player_user',
					   'uk_tg_game_round_actor',
					   'uk_tg_game_action_request'
					 )
					   AND contype = 'u'
					""")).isEqualTo(4);
			assertThat(singleString(statement, """
					SELECT pg_get_constraintdef(oid)
					  FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group_message'::regclass
					   AND conname = 'ck_table_group_message_type'
					""")).contains("GAME");
		}
	}

	@Test
	void partialHibernateSchemaIsReconciledAndRerunnable() throws Exception {
		createHibernateLikeGameTablesWithWrongDomainConstraints();

		execute(gameMigrationSql());
		execute(gameMigrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conrelid IN (
					   'tbl_table_group_game'::regclass,
					   'tbl_table_group_game_player'::regclass,
					   'tbl_table_group_game_action'::regclass
					 )
					   AND conname IN (
					     'fk_tg_game_group','uk_tg_game_create_request',
					     'ck_tg_game_topic','ck_tg_game_mode','ck_tg_game_status',
					     'ck_tg_game_phase','ck_tg_game_round','ck_tg_game_revision',
					     'ck_tg_game_created_username','ck_tg_game_selected_username',
					     'ck_tg_game_status_phase','ck_tg_game_state_shape',
					     'fk_tg_game_player_game','uk_tg_game_player_user',
					     'ck_tg_game_player_username','ck_tg_game_player_status',
					     'fk_tg_game_action_game','fk_tg_game_action_actor',
					     'fk_tg_game_action_target','uk_tg_game_round_actor',
					     'uk_tg_game_action_request','ck_tg_game_action_round',
					     'ck_tg_game_action_phase','ck_tg_game_action_type',
					     'ck_tg_game_action_shape'
					   )
					""")).isEqualTo(25);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint
					 WHERE conrelid IN (
					   'tbl_table_group_game'::regclass,
					   'tbl_table_group_game_player'::regclass,
					   'tbl_table_group_game_action'::regclass
					 )
					   AND conname LIKE ANY (ARRAY['fk_tg_game_%','ck_tg_game_%'])
					   AND NOT convalidated
					""")).isZero();
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM pg_constraint constraint_row
					  JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
					 WHERE table_row.relname IN (
					   'tbl_table_group_game',
					   'tbl_table_group_game_player',
					   'tbl_table_group_game_action'
					 )
					   AND constraint_row.contype = 'p'
					   AND pg_get_constraintdef(constraint_row.oid) = 'PRIMARY KEY (id)'
					""")).isEqualTo(3);

			assertThat(constraintDefinition(statement, "tbl_table_group_game", "fk_tg_game_group"))
					.contains("FOREIGN KEY (table_group_id)")
					.contains("REFERENCES tbl_table_group(id) ON DELETE CASCADE");
			assertThat(constraintDefinition(statement, "tbl_table_group_game", "uk_tg_game_create_request"))
					.isEqualTo("UNIQUE (table_group_id, created_by, create_request_id)");
			assertThat(constraintDefinition(statement, "tbl_table_group_game", "ck_tg_game_topic"))
					.contains("topic")
					.contains("WHO_PAYS")
					.doesNotContain("OTHER");
			assertThat(constraintDefinition(
					statement,
					"tbl_table_group_game_player",
					"uk_tg_game_player_user"
			)).isEqualTo("UNIQUE (game_id, user_id)");
			assertThat(constraintDefinition(
					statement,
					"tbl_table_group_game_action",
					"fk_tg_game_action_actor"
			))
					.contains("FOREIGN KEY (game_id, actor_user_id)")
					.contains("REFERENCES tbl_table_group_game_player(game_id, user_id) ON DELETE CASCADE");
			assertThat(constraintDefinition(
					statement,
					"tbl_table_group_game_action",
					"ck_tg_game_action_shape"
			)).contains("target_user_id = actor_user_id").doesNotContain("CHECK (true)");
			assertThat(singleString(statement, """
					SELECT indexdef
					  FROM pg_indexes
					 WHERE schemaname = current_schema()
					   AND indexname = 'uk_tg_game_one_active'
					"""))
					.contains("UNIQUE INDEX")
					.contains("WHERE")
					.contains("status")
					.contains("LOBBY")
					.contains("IN_PROGRESS");
		}
	}

	@Test
	void partialUniqueIndexAllowsTerminalHistoryButOnlyOneActiveGame() throws Exception {
		execute(gameMigrationSql());
		insertTableGroup();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertLobbyGame(statement, GAME_ID, "40000000-0000-0000-0000-000000000001");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_game (
					 id,version,table_group_id,created_by,created_by_username,create_request_id,
					 topic,mode,status,phase,round_number,action_deadline_at
					) VALUES (
					 '30000000-0000-0000-0000-000000000002',0,
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','owner_user',
					 '40000000-0000-0000-0000-000000000002',
					 'WHO_PAYS','DICE','IN_PROGRESS','DICE',1,
					 CURRENT_TIMESTAMP + INTERVAL '20 seconds'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23505");

			statement.execute("""
					UPDATE tbl_table_group_game
					   SET status='CANCELLED', phase='CANCELLED', join_deadline_at=NULL,
					       completed_at=CURRENT_TIMESTAMP, cancellation_reason='CREATOR_CANCELLED'
					 WHERE id='30000000-0000-0000-0000-000000000001'
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_game (
					 id,version,table_group_id,created_by,created_by_username,create_request_id,
					 topic,mode,status,phase,round_number,action_deadline_at
					) VALUES (
					 '30000000-0000-0000-0000-000000000003',0,
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','owner_user',
					 '40000000-0000-0000-0000-000000000003',
					 'WHO_PAYS','DICE','IN_PROGRESS','DICE',1,
					 CURRENT_TIMESTAMP + INTERVAL '20 seconds'
					)
					""");
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_table_group_game"))
					.isEqualTo(2);
		}
	}

	@Test
	void actionChecksRejectInvalidShapesAndCrossGameTargets() throws Exception {
		execute(gameMigrationSql());
		insertTableGroup();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertInProgressGame(statement, GAME_ID, "40000000-0000-0000-0000-000000000001");
			insertPlayer(statement, "50000000-0000-0000-0000-000000000001", GAME_ID, OWNER_ID, "owner_user");
			insertPlayer(statement, "50000000-0000-0000-0000-000000000002", GAME_ID, GUEST_ID, "guest_user");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_game (
					 id,version,table_group_id,created_by,created_by_username,create_request_id,
					 topic,mode,status,phase,round_number,completed_at,
					 selected_user_id,selected_username,result_message
					) VALUES (
					 '30000000-0000-0000-0000-000000000002',0,
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','owner_user',
					 '40000000-0000-0000-0000-000000000002',
					 'WHO_PAYS','DICE','COMPLETED','COMPLETED',1,CURRENT_TIMESTAMP,
					 '20000000-0000-0000-0000-000000000001','owner_user','missing outcome'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23514");

			String otherGameId = "30000000-0000-0000-0000-000000000009";
			insertCancelledGame(
					statement,
					otherGameId,
					"40000000-0000-0000-0000-000000000009"
			);
			insertPlayer(
					statement,
					"50000000-0000-0000-0000-000000000009",
					otherGameId,
					OTHER_GAME_PLAYER_ID,
					"other_user"
			);

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_game_action (
					 id,game_id,request_id,round_number,phase,actor_user_id,action,value,revealed
					) VALUES (
					 '60000000-0000-0000-0000-000000000001',
					 '30000000-0000-0000-0000-000000000001',
					 '70000000-0000-0000-0000-000000000001',1,'RPS',
					 '20000000-0000-0000-0000-000000000001','ROLL',6,false
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23514");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_game_action (
					 id,game_id,request_id,round_number,phase,actor_user_id,action,revealed
					) VALUES (
					 '60000000-0000-0000-0000-000000000005',
					 '30000000-0000-0000-0000-000000000001',
					 '70000000-0000-0000-0000-000000000005',1,'DICE',
					 '20000000-0000-0000-0000-000000000001','ROLL',false
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23514");

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_game_action (
					 id,game_id,request_id,round_number,phase,actor_user_id,action,target_user_id,revealed
					) VALUES (
					 '60000000-0000-0000-0000-000000000002',
					 '30000000-0000-0000-0000-000000000001',
					 '70000000-0000-0000-0000-000000000002',1,'VOTE',
					 '20000000-0000-0000-0000-000000000001','VOLUNTEER',
					 '20000000-0000-0000-0000-000000000002',false
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23514");

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_game_action (
					 id,game_id,request_id,round_number,phase,actor_user_id,action,target_user_id,revealed
					) VALUES (
					 '60000000-0000-0000-0000-000000000003',
						 '30000000-0000-0000-0000-000000000001',
						 '70000000-0000-0000-0000-000000000003',1,'VOTE',
						 '20000000-0000-0000-0000-000000000001','VOTE',
						 '20000000-0000-0000-0000-000000000003',false
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23503");

			statement.execute("""
					INSERT INTO tbl_table_group_game_action (
					 id,game_id,request_id,round_number,phase,actor_user_id,action,target_user_id,revealed
					) VALUES (
					 '60000000-0000-0000-0000-000000000004',
					 '30000000-0000-0000-0000-000000000001',
					 '70000000-0000-0000-0000-000000000004',1,'VOTE',
					 '20000000-0000-0000-0000-000000000001','VOLUNTEER',
					 '20000000-0000-0000-0000-000000000001',false
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_game_action (
					 id,game_id,request_id,round_number,phase,actor_user_id,action,target_user_id,revealed
					) VALUES (
					 '60000000-0000-0000-0000-000000000006',
					 '30000000-0000-0000-0000-000000000001',
					 '70000000-0000-0000-0000-000000000006',1,'VOTE',
					 '20000000-0000-0000-0000-000000000002','VOTE',
					 '20000000-0000-0000-0000-000000000002',false
					)
					""");
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_table_group_game_action"))
					.isEqualTo(2);
		}
	}

	@Test
	void rerunAcceptsExactlyOneValidLiveGameAnchor() throws Exception {
		execute(gameMigrationSql());
		insertTableGroup();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertLobbyGame(statement, GAME_ID, "40000000-0000-0000-0000-000000000001");
			insertGameAnchor(
					statement,
					"80000000-0000-0000-0000-000000000001",
					GAME_ID,
					GROUP_ID,
					OWNER_ID
			);
		}

		execute(gameMigrationSql());
		execute(gameMigrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_table_group_message WHERE game_id='" + GAME_ID + "'"))
					.isEqualTo(1);
		}
	}

	@Test
	void hardeningRerunReconcilesMissingMessageTypeConstraintAfterGameRowsExist() throws Exception {
		execute(gameMigrationSql());
		insertTableGroup();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertLobbyGame(statement, GAME_ID, "40000000-0000-0000-0000-000000000001");
			insertGameAnchor(
					statement,
					"80000000-0000-0000-0000-000000000001",
					GAME_ID,
					GROUP_ID,
					OWNER_ID
			);
			statement.execute("""
					INSERT INTO tbl_table_group_gender_prefs (table_group_id,gender_pref) VALUES
					 ('10000000-0000-0000-0000-000000000001','OTHER'),
					 ('10000000-0000-0000-0000-000000000001','OTHER'),
					 ('10000000-0000-0000-0000-000000000001','OTHER'),
					 ('10000000-0000-0000-0000-000000000001','OTHER')
					""");
			statement.execute("ALTER TABLE tbl_table_group_message DROP CONSTRAINT ck_table_group_message_type");
		}

		execute(hardeningMigrationSql());
		execute(gameMigrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			assertThat(constraintDefinition(
					statement,
					"tbl_table_group_message",
					"ck_table_group_message_type"
			)).contains("GAME");
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_table_group_message WHERE game_id='" + GAME_ID + "'"))
					.isEqualTo(1);
		}
	}

	@Test
	void rerunFailsClosedWhenAGameHasNoAnchor() throws Exception {
		execute(gameMigrationSql());
		insertTableGroup();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertLobbyGame(statement, GAME_ID, "40000000-0000-0000-0000-000000000001");
		}

		assertThatThrownBy(() -> execute(gameMigrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("exactly one live GAME anchor");
	}

	@Test
	void rerunFailsClosedWhenTheAnchorIsDeleted() throws Exception {
		prepareGameWithValidAnchor();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("UPDATE tbl_table_group_message SET deleted_at=CURRENT_TIMESTAMP WHERE game_id='" + GAME_ID + "'");
		}

		assertInvalidLinkedAnchorPreflight();
	}

	@Test
	void rerunFailsClosedWhenTheAnchorPointsAtTheWrongTable() throws Exception {
		prepareGameWithValidAnchor();
		String otherGroupId = "10000000-0000-0000-0000-000000000002";
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '%s','%s','Other Venue',4,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE','%s'
					)
					""".formatted(otherGroupId, OWNER_ID, CITY_ID));
			statement.execute("UPDATE tbl_table_group_message SET table_group_id='" + otherGroupId
					+ "' WHERE game_id='" + GAME_ID + "'");
		}

		assertInvalidLinkedAnchorPreflight();
	}

	@Test
	void rerunFailsClosedWhenTheAnchorHasTheWrongType() throws Exception {
		prepareGameWithValidAnchor();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE tbl_table_group_message DROP CONSTRAINT ck_tg_message_game_shape");
			statement.execute("UPDATE tbl_table_group_message SET message_type='TEXT' WHERE game_id='" + GAME_ID + "'");
		}

		assertInvalidLinkedAnchorPreflight();
	}

	@Test
	void rerunFailsClosedWhenTheAnchorSenderIsNotTheGameCreator() throws Exception {
		prepareGameWithValidAnchor();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("UPDATE tbl_table_group_message SET sender_id='" + GUEST_ID
					+ "' WHERE game_id='" + GAME_ID + "'");
		}

		assertInvalidLinkedAnchorPreflight();
	}

	@Test
	void rerunFailsClosedWhenAGameHasDuplicateLiveAnchors() throws Exception {
		prepareGameWithValidAnchor();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("DROP INDEX uk_tg_message_game");
			insertGameAnchor(
					statement,
					"80000000-0000-0000-0000-000000000002",
					GAME_ID,
					GROUP_ID,
					OWNER_ID
			);
		}

		assertThatThrownBy(() -> execute(gameMigrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("exactly one live GAME anchor");
	}

	@Test
	void rerunUpgradesTheDraftConstraintThatRejectedOrdinarySelfVote() throws Exception {
		execute(gameMigrationSql());
		insertTableGroup();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertInProgressGame(statement, GAME_ID, "40000000-0000-0000-0000-000000000001");
			insertGameAnchor(
					statement,
					"80000000-0000-0000-0000-000000000010",
					GAME_ID,
					GROUP_ID,
					OWNER_ID
			);
			insertPlayer(statement, "50000000-0000-0000-0000-000000000001", GAME_ID, OWNER_ID, "owner_user");
			statement.execute("ALTER TABLE tbl_table_group_game_action DROP CONSTRAINT ck_tg_game_action_shape");
			statement.execute("""
					ALTER TABLE tbl_table_group_game_action
					ADD CONSTRAINT ck_tg_game_action_shape CHECK (
					 phase <> 'VOTE'
					 OR action <> 'VOTE'
					 OR (target_user_id IS NOT NULL AND target_user_id <> actor_user_id AND value IS NULL)
					)
					""");
			assertThatThrownBy(() -> insertOrdinarySelfVote(statement))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23514");
		}

		execute(gameMigrationSql());

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertOrdinarySelfVote(statement);
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_table_group_game_action"))
					.isEqualTo(1);
			assertThat(singleString(statement, """
					SELECT pg_get_constraintdef(oid)
					  FROM pg_constraint
					 WHERE conrelid = 'tbl_table_group_game_action'::regclass
					   AND conname = 'ck_tg_game_action_shape'
					""")).doesNotContain("target_user_id <> actor_user_id");
		}
	}

	@Test
	void rerunUpgradesDraftUsernameChecksForValidUtf16EmojiSnapshots() throws Exception {
		execute(gameMigrationSql());
		insertTableGroup();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE tbl_table_group_game DROP CONSTRAINT ck_tg_game_created_username");
			statement.execute("ALTER TABLE tbl_table_group_game DROP CONSTRAINT ck_tg_game_selected_username");
			statement.execute("ALTER TABLE tbl_table_group_game_player DROP CONSTRAINT ck_tg_game_player_username");
			statement.execute("""
					ALTER TABLE tbl_table_group_game ADD CONSTRAINT ck_tg_game_created_username
					CHECK (char_length(btrim(created_by_username)) BETWEEN 3 AND 30)
					""");
			statement.execute("""
					ALTER TABLE tbl_table_group_game ADD CONSTRAINT ck_tg_game_selected_username
					CHECK (selected_username IS NULL OR char_length(btrim(selected_username)) BETWEEN 3 AND 30)
					""");
			statement.execute("""
					ALTER TABLE tbl_table_group_game_player ADD CONSTRAINT ck_tg_game_player_username
					CHECK (char_length(btrim(username)) BETWEEN 3 AND 30)
					""");
		}

		execute(gameMigrationSql());

		String validUtf16Username = "\uD83D\uDE00\uD83D\uDE00";
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO tbl_table_group_game (
					 id,version,table_group_id,created_by,created_by_username,create_request_id,
					 topic,mode,status,phase,round_number,completed_at,selected_user_id,
					 selected_username,outcome,result_message
					) VALUES (
					 '30000000-0000-0000-0000-000000000001',0,
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','%s',
					 '40000000-0000-0000-0000-000000000001',
					 'WHO_PAYS','VOTE','COMPLETED','COMPLETED',1,CURRENT_TIMESTAMP,
					 '20000000-0000-0000-0000-000000000001','%s','ASSIGNED','result'
					)
					""".formatted(validUtf16Username, validUtf16Username));
			insertPlayer(
					statement,
					"50000000-0000-0000-0000-000000000001",
					GAME_ID,
					OWNER_ID,
					validUtf16Username
			);
			assertThat(singleInt(statement, """
					SELECT count(*)
					  FROM tbl_table_group_game game
					  JOIN tbl_table_group_game_player player ON player.game_id = game.id
					 WHERE game.created_by_username = player.username
					   AND game.selected_username = player.username
					""")).isEqualTo(1);
		}
	}

	@Test
	void durableRequestKeysRejectConflictingCreateAndActionReplays() throws Exception {
		execute(gameMigrationSql());
		insertTableGroup();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			String createRequestId = "40000000-0000-0000-0000-000000000001";
			insertLobbyGame(statement, GAME_ID, createRequestId);
			statement.execute("""
					UPDATE tbl_table_group_game
					   SET status='CANCELLED', phase='CANCELLED', join_deadline_at=NULL,
					       completed_at=CURRENT_TIMESTAMP, cancellation_reason='CREATOR_CANCELLED'
					 WHERE id='30000000-0000-0000-0000-000000000001'
					""");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_game (
					 id,version,table_group_id,created_by,created_by_username,create_request_id,
					 topic,mode,status,phase,round_number,completed_at,cancellation_reason
					) VALUES (
					 '30000000-0000-0000-0000-000000000002',0,
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','owner_user',
					 '40000000-0000-0000-0000-000000000001',
					 'WHO_PAYS','VOTE','CANCELLED','CANCELLED',0,
					 CURRENT_TIMESTAMP,'CREATOR_CANCELLED'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23505");

			String actionGameId = "30000000-0000-0000-0000-000000000003";
			insertInProgressGame(
					statement,
					actionGameId,
					"40000000-0000-0000-0000-000000000003"
			);
			insertPlayer(
					statement,
					"50000000-0000-0000-0000-000000000001",
					actionGameId,
					OWNER_ID,
					"owner_user"
			);
			statement.execute("""
					INSERT INTO tbl_table_group_game_action (
					 id,game_id,request_id,round_number,phase,actor_user_id,action,revealed
					) VALUES (
					 '60000000-0000-0000-0000-000000000001',
					 '30000000-0000-0000-0000-000000000003',
					 '70000000-0000-0000-0000-000000000001',1,'RPS',
					 '20000000-0000-0000-0000-000000000001','ROCK',false
					)
					""");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_game_action (
					 id,game_id,request_id,round_number,phase,actor_user_id,action,revealed
					) VALUES (
					 '60000000-0000-0000-0000-000000000002',
					 '30000000-0000-0000-0000-000000000003',
					 '70000000-0000-0000-0000-000000000001',2,'RPS',
					 '20000000-0000-0000-0000-000000000001','PAPER',false
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23505");
		}
	}

	@Test
	void gameCardShapeAndCascadeKeepOrdinaryChatIndependent() throws Exception {
		execute(gameMigrationSql());
		insertTableGroup();

		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertInProgressGame(statement, GAME_ID, "40000000-0000-0000-0000-000000000001");
			insertPlayer(statement, "50000000-0000-0000-0000-000000000001", GAME_ID, OWNER_ID, "owner_user");
			statement.execute("""
					INSERT INTO tbl_table_group_game_action (
					 id,game_id,request_id,round_number,phase,actor_user_id,action,revealed
					) VALUES (
					 '60000000-0000-0000-0000-000000000001',
					 '30000000-0000-0000-0000-000000000001',
					 '70000000-0000-0000-0000-000000000001',1,'RPS',
					 '20000000-0000-0000-0000-000000000001','ROCK',false
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_message (
					 id,created_at,table_group_id,sender_id,content,message_type,game_id
					) VALUES (
					 '80000000-0000-0000-0000-000000000001',
					 CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001',
					 'Hesap Kimde?','GAME',
					 '30000000-0000-0000-0000-000000000001'
					)
					""");
			statement.execute("""
					INSERT INTO tbl_table_group_message (
					 id,created_at,table_group_id,sender_id,content,message_type
					) VALUES (
					 '80000000-0000-0000-0000-000000000002',
					 CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','normal chat','TEXT'
					)
					""");

			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_message (
					 id,created_at,table_group_id,sender_id,content,message_type,game_id
					) VALUES (
					 '80000000-0000-0000-0000-000000000003',
					 CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','spoof','TEXT',
					 '30000000-0000-0000-0000-000000000001'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23514");
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO tbl_table_group_message (
					 id,created_at,table_group_id,sender_id,content,message_type
					) VALUES (
					 '80000000-0000-0000-0000-000000000004',
					 CURRENT_TIMESTAMP AT TIME ZONE 'UTC',
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','orphan game card','GAME'
					)
					"""))
					.isInstanceOf(SQLException.class)
					.hasFieldOrPropertyWithValue("SQLState", "23514");

			statement.execute("DELETE FROM tbl_table_group_game WHERE id='" + GAME_ID + "'");

			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_table_group_game_player"))
					.isZero();
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_table_group_game_action"))
					.isZero();
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_table_group_message WHERE message_type='GAME'"))
					.isZero();
			assertThat(singleInt(statement, "SELECT count(*) FROM tbl_table_group_message WHERE message_type='TEXT'"))
					.isEqualTo(1);
		}
	}

	private static void insertTableGroup() throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("INSERT INTO tbl_city VALUES ('" + CITY_ID + "')");
			statement.execute("""
					INSERT INTO tbl_table_group (
					 id,owner_id,venue_name,max_person_count,age_min,age_max,
					 start_at,expires_at,status,city_id
					) VALUES (
					 '10000000-0000-0000-0000-000000000001',
					 '20000000-0000-0000-0000-000000000001','Test Venue',4,20,40,
					 CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '1 hour','ACTIVE',
					 '00000000-0000-0000-0000-000000000001'
					)
					""");
		}
	}

	private static void createHibernateLikeGameTablesWithWrongDomainConstraints()
			throws SQLException {
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE tbl_table_group_game (
					 id uuid PRIMARY KEY,
					 created_at timestamp without time zone,
					 updated_at timestamp without time zone,
					 version bigint NOT NULL DEFAULT 0,
					 revision bigint NOT NULL DEFAULT 1,
					 table_group_id uuid NOT NULL,
					 created_by uuid NOT NULL,
					 created_by_username varchar(30) NOT NULL,
					 create_request_id uuid NOT NULL,
					 topic varchar(32) NOT NULL,
					 mode varchar(32) NOT NULL,
					 status varchar(24) NOT NULL,
					 phase varchar(24) NOT NULL,
					 round_number integer NOT NULL DEFAULT 0,
					 join_deadline_at timestamp with time zone,
					 action_deadline_at timestamp with time zone,
					 completed_at timestamp with time zone,
					 selected_user_id uuid,
					 selected_username varchar(30),
					 outcome varchar(24),
					 result_message varchar(500),
					 cancellation_reason varchar(64),
					 CONSTRAINT fk_tg_game_group
					   FOREIGN KEY (created_by) REFERENCES tbl_table_group(id),
					 CONSTRAINT uk_tg_game_create_request
					   UNIQUE (id, created_by, create_request_id),
					 CONSTRAINT ck_tg_game_topic CHECK (topic IN ('WHO_PAYS', 'OTHER'))
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_table_group_game_player (
					 id uuid PRIMARY KEY,
					 created_at timestamp without time zone,
					 updated_at timestamp without time zone,
					 game_id uuid NOT NULL,
					 user_id uuid NOT NULL,
					 username varchar(30) NOT NULL,
					 status varchar(24) NOT NULL,
					 joined_at timestamp with time zone NOT NULL,
					 CONSTRAINT fk_tg_game_player_game
					   FOREIGN KEY (user_id) REFERENCES tbl_table_group_game(id),
					 CONSTRAINT uk_tg_game_player_user UNIQUE (id, user_id),
					 CONSTRAINT ck_tg_game_player_status CHECK (status IN ('ACTIVE', 'ZOMBIE'))
					)
					""");
			statement.execute("""
					CREATE TABLE tbl_table_group_game_action (
					 id uuid PRIMARY KEY,
					 created_at timestamp without time zone,
					 updated_at timestamp without time zone,
					 game_id uuid NOT NULL,
					 request_id uuid NOT NULL,
					 round_number integer NOT NULL,
					 phase varchar(24) NOT NULL,
					 actor_user_id uuid NOT NULL,
					 action varchar(24) NOT NULL,
					 target_user_id uuid,
					 value integer,
					 revealed boolean NOT NULL DEFAULT false,
					 CONSTRAINT fk_tg_game_action_game
					   FOREIGN KEY (actor_user_id) REFERENCES tbl_table_group_game(id),
					 CONSTRAINT fk_tg_game_action_actor
					   FOREIGN KEY (target_user_id) REFERENCES tbl_table_group_game_player(id),
					 CONSTRAINT fk_tg_game_action_target
					   FOREIGN KEY (actor_user_id) REFERENCES tbl_table_group_game_player(id),
					 CONSTRAINT uk_tg_game_round_actor UNIQUE (id, round_number, actor_user_id),
					 CONSTRAINT uk_tg_game_action_request UNIQUE (id, actor_user_id, request_id),
					 CONSTRAINT ck_tg_game_action_shape CHECK (true)
					)
					""");
			statement.execute("""
					ALTER TABLE tbl_table_group_message
					  ADD COLUMN game_id uuid,
					  ADD CONSTRAINT uk_tg_message_game UNIQUE (game_id)
					""");
		}
	}

	private static void insertLobbyGame(Statement statement, String gameId, String requestId)
			throws SQLException {
		statement.execute("""
				INSERT INTO tbl_table_group_game (
				 id,version,table_group_id,created_by,created_by_username,create_request_id,
				 topic,mode,status,phase,round_number,join_deadline_at
				) VALUES (
				 '%s',0,'%s','%s','owner_user','%s',
				 'WHO_PAYS','VOTE','LOBBY','LOBBY',0,
				 CURRENT_TIMESTAMP + INTERVAL '3 minutes'
				)
				""".formatted(gameId, GROUP_ID, OWNER_ID, requestId));
	}

	private static void insertInProgressGame(Statement statement, String gameId, String requestId)
			throws SQLException {
		statement.execute("""
				INSERT INTO tbl_table_group_game (
				 id,version,table_group_id,created_by,created_by_username,create_request_id,
				 topic,mode,status,phase,round_number,action_deadline_at
				) VALUES (
				 '%s',0,'%s','%s','owner_user','%s',
				 'WHO_PAYS','ROCK_PAPER_SCISSORS','IN_PROGRESS','RPS',1,
				 CURRENT_TIMESTAMP + INTERVAL '20 seconds'
				)
				""".formatted(gameId, GROUP_ID, OWNER_ID, requestId));
	}

	private static void insertCancelledGame(Statement statement, String gameId, String requestId)
			throws SQLException {
		statement.execute("""
				INSERT INTO tbl_table_group_game (
				 id,version,table_group_id,created_by,created_by_username,create_request_id,
				 topic,mode,status,phase,round_number,completed_at,cancellation_reason
				) VALUES (
				 '%s',0,'%s','%s','owner_user','%s',
				 'WHO_PAYS','VOTE','CANCELLED','CANCELLED',0,
				 CURRENT_TIMESTAMP,'CREATOR_CANCELLED'
				)
				""".formatted(gameId, GROUP_ID, OWNER_ID, requestId));
	}

	private static void insertGameAnchor(
			Statement statement,
			String messageId,
			String gameId,
			String tableGroupId,
			String senderId
	) throws SQLException {
		statement.execute("""
				INSERT INTO tbl_table_group_message (
				 id,created_at,table_group_id,sender_id,content,message_type,game_id
				) VALUES (
				 '%s',CURRENT_TIMESTAMP AT TIME ZONE 'UTC','%s','%s',
				 'Hesap Kimde?','GAME','%s'
				)
				""".formatted(messageId, tableGroupId, senderId, gameId));
	}

	private void prepareGameWithValidAnchor() throws Exception {
		execute(gameMigrationSql());
		insertTableGroup();
		try (Connection connection = connection(); Statement statement = connection.createStatement()) {
			insertLobbyGame(statement, GAME_ID, "40000000-0000-0000-0000-000000000001");
			insertGameAnchor(
					statement,
					"80000000-0000-0000-0000-000000000001",
					GAME_ID,
					GROUP_ID,
					OWNER_ID
			);
		}
	}

	private void assertInvalidLinkedAnchorPreflight() {
		assertThatThrownBy(() -> execute(gameMigrationSql()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("linked messages must be live GAME cards");
	}

	private static void insertPlayer(
			Statement statement,
			String id,
			String gameId,
			String userId,
			String username
	) throws SQLException {
		statement.execute("""
				INSERT INTO tbl_table_group_game_player (
				 id,game_id,user_id,username,status,joined_at
				) VALUES ('%s','%s','%s','%s','ACTIVE',CURRENT_TIMESTAMP)
				""".formatted(id, gameId, userId, username));
	}

	private static void insertOrdinarySelfVote(Statement statement) throws SQLException {
		statement.execute("""
				INSERT INTO tbl_table_group_game_action (
				 id,game_id,request_id,round_number,phase,actor_user_id,action,target_user_id,revealed
				) VALUES (
				 '60000000-0000-0000-0000-000000000010',
				 '30000000-0000-0000-0000-000000000001',
				 '70000000-0000-0000-0000-000000000010',1,'VOTE',
				 '20000000-0000-0000-0000-000000000001','VOTE',
				 '20000000-0000-0000-0000-000000000001',false
				)
				""");
	}

	private static String hardeningMigrationSql() throws Exception {
		return Files.readString(Path.of(System.getProperty("user.dir"), "scripts", "db",
				"2026-08-17-tablegroup-hardening.sql"));
	}

	private static String gameMigrationSql() throws Exception {
		return Files.readString(Path.of(System.getProperty("user.dir"), "scripts", "db",
				"2026-08-30-tablegroup-who-pays-game.sql"));
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

	private static String singleString(Statement statement, String sql) throws SQLException {
		try (ResultSet result = statement.executeQuery(sql)) {
			assertThat(result.next()).isTrue();
			return result.getString(1);
		}
	}

	private static String constraintDefinition(
			Statement statement,
			String tableName,
			String constraintName
	) throws SQLException {
		return singleString(statement, """
				SELECT pg_get_constraintdef(oid)
				  FROM pg_constraint
				 WHERE conrelid = '%s'::regclass
				   AND conname = '%s'
				""".formatted(tableName, constraintName));
	}

	private static Connection connection() throws SQLException {
		return DriverManager.getConnection(
				POSTGRES.getJdbcUrl(),
				POSTGRES.getUsername(),
				POSTGRES.getPassword()
		);
	}
}
