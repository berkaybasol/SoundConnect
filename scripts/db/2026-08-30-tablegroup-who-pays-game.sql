-- SoundConnect TableGroup "Hesap Kimde?" game rollout.
--
-- Prerequisite: scripts/db/2026-08-17-tablegroup-hardening.sql
-- Run with: psql -v ON_ERROR_STOP=1 -f scripts/db/2026-08-30-tablegroup-who-pays-game.sql
--
-- The migration is transactional and rerunnable. Game rows are the durable
-- source of truth; the linked GAME chat message is a server-owned card and is
-- removed automatically when its game is deleted by cancellation or retention.

BEGIN;

CREATE TABLE IF NOT EXISTS tbl_table_group_game (
    id uuid NOT NULL,
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
    CONSTRAINT pk_tg_game PRIMARY KEY (id),
    CONSTRAINT fk_tg_game_group
        FOREIGN KEY (table_group_id) REFERENCES tbl_table_group (id) ON DELETE CASCADE,
    CONSTRAINT uk_tg_game_create_request
        UNIQUE (table_group_id, created_by, create_request_id),
    CONSTRAINT ck_tg_game_topic CHECK (topic = 'WHO_PAYS'),
    CONSTRAINT ck_tg_game_mode
        CHECK (mode IN ('ROCK_PAPER_SCISSORS', 'DICE', 'VOTE')),
    CONSTRAINT ck_tg_game_status
        CHECK (status IN ('LOBBY', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_tg_game_phase
        CHECK (phase IN (
            'LOBBY', 'RPS', 'DICE', 'VOTE', 'VOTE_TIE_DICE', 'COMPLETED', 'CANCELLED'
        )),
    CONSTRAINT ck_tg_game_round CHECK (round_number >= 0),
    CONSTRAINT ck_tg_game_revision CHECK (revision >= 1),
    CONSTRAINT ck_tg_game_created_username
        CHECK (btrim(created_by_username) <> ''),
    CONSTRAINT ck_tg_game_selected_username
        CHECK (
            selected_username IS NULL
            OR btrim(selected_username) <> ''
        ),
    CONSTRAINT ck_tg_game_status_phase CHECK (
        (status = 'LOBBY' AND phase = 'LOBBY')
        OR (status = 'IN_PROGRESS' AND phase IN ('RPS', 'DICE', 'VOTE', 'VOTE_TIE_DICE'))
        OR (status = 'COMPLETED' AND phase = 'COMPLETED')
        OR (status = 'CANCELLED' AND phase = 'CANCELLED')
    ),
    CONSTRAINT ck_tg_game_state_shape CHECK (
        (
            status = 'LOBBY'
            AND round_number = 0
            AND join_deadline_at IS NOT NULL
            AND action_deadline_at IS NULL
            AND completed_at IS NULL
            AND selected_user_id IS NULL
            AND selected_username IS NULL
            AND outcome IS NULL
            AND result_message IS NULL
            AND cancellation_reason IS NULL
        )
        OR (
            status = 'IN_PROGRESS'
            AND round_number >= 1
            AND join_deadline_at IS NULL
            AND action_deadline_at IS NOT NULL
            AND completed_at IS NULL
            AND selected_user_id IS NULL
            AND selected_username IS NULL
            AND outcome IS NULL
            AND result_message IS NULL
            AND cancellation_reason IS NULL
        )
        OR (
            status = 'COMPLETED'
            AND round_number >= 1
            AND join_deadline_at IS NULL
            AND action_deadline_at IS NULL
            AND completed_at IS NOT NULL
            AND selected_user_id IS NOT NULL
            AND selected_username IS NOT NULL
            AND outcome IS NOT NULL
            AND outcome IN ('ASSIGNED', 'VOLUNTEER')
            AND result_message IS NOT NULL
            AND char_length(btrim(result_message)) BETWEEN 1 AND 500
            AND cancellation_reason IS NULL
        )
        OR (
            status = 'CANCELLED'
            AND join_deadline_at IS NULL
            AND action_deadline_at IS NULL
            AND completed_at IS NOT NULL
            AND selected_user_id IS NULL
            AND selected_username IS NULL
            AND outcome IS NULL
            AND result_message IS NULL
            AND cancellation_reason IS NOT NULL
            AND char_length(btrim(cancellation_reason)) BETWEEN 1 AND 64
        )
    )
);

CREATE TABLE IF NOT EXISTS tbl_table_group_game_player (
    id uuid NOT NULL,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    game_id uuid NOT NULL,
    user_id uuid NOT NULL,
    username varchar(30) NOT NULL,
    status varchar(24) NOT NULL,
    joined_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_tg_game_player PRIMARY KEY (id),
    CONSTRAINT fk_tg_game_player_game
        FOREIGN KEY (game_id) REFERENCES tbl_table_group_game (id) ON DELETE CASCADE,
    CONSTRAINT uk_tg_game_player_user UNIQUE (game_id, user_id),
    CONSTRAINT ck_tg_game_player_username
        CHECK (btrim(username) <> ''),
    CONSTRAINT ck_tg_game_player_status
        CHECK (status IN ('ACTIVE', 'SAFE', 'TIMED_OUT', 'LEFT'))
);

CREATE TABLE IF NOT EXISTS tbl_table_group_game_action (
    id uuid NOT NULL,
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
    CONSTRAINT pk_tg_game_action PRIMARY KEY (id),
    CONSTRAINT fk_tg_game_action_game
        FOREIGN KEY (game_id) REFERENCES tbl_table_group_game (id) ON DELETE CASCADE,
    CONSTRAINT fk_tg_game_action_actor
        FOREIGN KEY (game_id, actor_user_id)
        REFERENCES tbl_table_group_game_player (game_id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_tg_game_action_target
        FOREIGN KEY (game_id, target_user_id)
        REFERENCES tbl_table_group_game_player (game_id, user_id) ON DELETE CASCADE,
    CONSTRAINT uk_tg_game_round_actor
        UNIQUE (game_id, round_number, actor_user_id),
    CONSTRAINT uk_tg_game_action_request
        UNIQUE (game_id, actor_user_id, request_id),
    CONSTRAINT ck_tg_game_action_round CHECK (round_number >= 1),
    CONSTRAINT ck_tg_game_action_phase
        CHECK (phase IN ('RPS', 'DICE', 'VOTE', 'VOTE_TIE_DICE')),
    CONSTRAINT ck_tg_game_action_type
        CHECK (action IN ('ROCK', 'PAPER', 'SCISSORS', 'ROLL', 'VOTE', 'VOLUNTEER')),
    CONSTRAINT ck_tg_game_action_shape CHECK (
        (
            phase = 'RPS'
            AND action IN ('ROCK', 'PAPER', 'SCISSORS')
            AND target_user_id IS NULL
            AND value IS NULL
        )
        OR (
            phase IN ('DICE', 'VOTE_TIE_DICE')
            AND action = 'ROLL'
            AND target_user_id IS NULL
            AND value IS NOT NULL
            AND value BETWEEN 1 AND 6
        )
        OR (
            phase = 'VOTE'
            AND action = 'VOTE'
            AND target_user_id IS NOT NULL
            AND value IS NULL
        )
        OR (
            phase = 'VOTE'
            AND action = 'VOLUNTEER'
            AND target_user_id IS NOT NULL
            AND target_user_id = actor_user_id
            AND value IS NULL
        )
    )
);

-- Add the link column before reconciling constraints so legacy/partial data can
-- be validated as one coherent aggregate. Do not synthesize missing anchors:
-- runtime reads require one live, server-owned GAME card for every game.
ALTER TABLE tbl_table_group_message
    ADD COLUMN IF NOT EXISTS game_id uuid;

DO $table_group_game_anchor_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group_message message
          LEFT JOIN tbl_table_group_game game
            ON game.id = message.game_id
         WHERE (message.message_type = 'GAME' AND message.game_id IS NULL)
            OR (
                message.game_id IS NOT NULL
                AND (
                    game.id IS NULL
                    OR message.table_group_id IS DISTINCT FROM game.table_group_id
                    OR message.sender_id IS DISTINCT FROM game.created_by
                    OR message.message_type IS DISTINCT FROM 'GAME'
                    OR message.deleted_at IS NOT NULL
                )
            )
    ) THEN
        RAISE EXCEPTION
            'TableGroup game anchor preflight failed: linked messages must be live GAME cards for the same game creator and table';
    END IF;

    IF EXISTS (
        SELECT game.id
          FROM tbl_table_group_game game
          LEFT JOIN tbl_table_group_message message
            ON message.game_id = game.id
         GROUP BY game.id
        HAVING count(message.id) <> 1
    ) THEN
        RAISE EXCEPTION
            'TableGroup game anchor preflight failed: every game must have exactly one live GAME anchor';
    END IF;
END
$table_group_game_anchor_preflight$;

-- CREATE TABLE IF NOT EXISTS deliberately does not reconcile an existing
-- Hibernate-created table. Rebuild every game-domain constraint below so a
-- partial schema cannot silently keep a missing or same-name/wrong definition.
-- Foreign keys and checks are installed NOT VALID first, which keeps the
-- definition change short and then performs an explicit, fail-closed scan of
-- existing rows. PostgreSQL does not support NOT VALID for UNIQUE constraints;
-- their backing indexes therefore validate existing data while being created.

-- A Hibernate-created table normally already has its primary key, although its
-- generated name is not stable. Accept any correctly-shaped primary key and
-- reject an incompatible one rather than attempting a destructive key rewrite.
DO $table_group_game_primary_keys$
DECLARE
    table_name text;
    expected_column smallint;
    actual_columns smallint[];
BEGIN
    FOREACH table_name IN ARRAY ARRAY[
        'tbl_table_group_game',
        'tbl_table_group_game_player',
        'tbl_table_group_game_action'
    ] LOOP
        SELECT attnum
          INTO expected_column
          FROM pg_attribute
         WHERE attrelid = table_name::regclass
           AND attname = 'id'
           AND NOT attisdropped;

        SELECT conkey
          INTO actual_columns
          FROM pg_constraint
         WHERE conrelid = table_name::regclass
           AND contype = 'p';

        IF actual_columns IS NULL THEN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I PRIMARY KEY (id)',
                table_name,
                CASE table_name
                    WHEN 'tbl_table_group_game' THEN 'pk_tg_game'
                    WHEN 'tbl_table_group_game_player' THEN 'pk_tg_game_player'
                    ELSE 'pk_tg_game_action'
                END
            );
        ELSIF actual_columns <> ARRAY[expected_column]::smallint[] THEN
            RAISE EXCEPTION
                'Table % has an incompatible primary key; expected exactly (id)',
                table_name;
        END IF;
    END LOOP;
END
$table_group_game_primary_keys$;

-- Drop dependent foreign keys before rebuilding the candidate unique key on
-- (game_id, user_id). This also replaces same-name foreign keys that point at
-- the wrong table or use the wrong delete action.
ALTER TABLE tbl_table_group_game_action
    DROP CONSTRAINT IF EXISTS fk_tg_game_action_actor;
ALTER TABLE tbl_table_group_game_action
    DROP CONSTRAINT IF EXISTS fk_tg_game_action_target;
ALTER TABLE tbl_table_group_game_action
    DROP CONSTRAINT IF EXISTS fk_tg_game_action_game;
ALTER TABLE tbl_table_group_game_player
    DROP CONSTRAINT IF EXISTS fk_tg_game_player_game;
ALTER TABLE tbl_table_group_game
    DROP CONSTRAINT IF EXISTS fk_tg_game_group;

ALTER TABLE tbl_table_group_game
    DROP CONSTRAINT IF EXISTS uk_tg_game_create_request;
ALTER TABLE tbl_table_group_game_player
    DROP CONSTRAINT IF EXISTS uk_tg_game_player_user;
ALTER TABLE tbl_table_group_game_action
    DROP CONSTRAINT IF EXISTS uk_tg_game_round_actor;
ALTER TABLE tbl_table_group_game_action
    DROP CONSTRAINT IF EXISTS uk_tg_game_action_request;

ALTER TABLE tbl_table_group_game
    ADD CONSTRAINT uk_tg_game_create_request
    UNIQUE (table_group_id, created_by, create_request_id);
ALTER TABLE tbl_table_group_game_player
    ADD CONSTRAINT uk_tg_game_player_user UNIQUE (game_id, user_id);
ALTER TABLE tbl_table_group_game_action
    ADD CONSTRAINT uk_tg_game_round_actor
    UNIQUE (game_id, round_number, actor_user_id);
ALTER TABLE tbl_table_group_game_action
    ADD CONSTRAINT uk_tg_game_action_request
    UNIQUE (game_id, actor_user_id, request_id);

ALTER TABLE tbl_table_group_game
    ADD CONSTRAINT fk_tg_game_group
    FOREIGN KEY (table_group_id) REFERENCES tbl_table_group (id)
    ON DELETE CASCADE NOT VALID;
ALTER TABLE tbl_table_group_game_player
    ADD CONSTRAINT fk_tg_game_player_game
    FOREIGN KEY (game_id) REFERENCES tbl_table_group_game (id)
    ON DELETE CASCADE NOT VALID;
ALTER TABLE tbl_table_group_game_action
    ADD CONSTRAINT fk_tg_game_action_game
    FOREIGN KEY (game_id) REFERENCES tbl_table_group_game (id)
    ON DELETE CASCADE NOT VALID;
ALTER TABLE tbl_table_group_game_action
    ADD CONSTRAINT fk_tg_game_action_actor
    FOREIGN KEY (game_id, actor_user_id)
    REFERENCES tbl_table_group_game_player (game_id, user_id)
    ON DELETE CASCADE NOT VALID;
ALTER TABLE tbl_table_group_game_action
    ADD CONSTRAINT fk_tg_game_action_target
    FOREIGN KEY (game_id, target_user_id)
    REFERENCES tbl_table_group_game_player (game_id, user_id)
    ON DELETE CASCADE NOT VALID;

ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT fk_tg_game_group;
ALTER TABLE tbl_table_group_game_player
    VALIDATE CONSTRAINT fk_tg_game_player_game;
ALTER TABLE tbl_table_group_game_action
    VALIDATE CONSTRAINT fk_tg_game_action_game;
ALTER TABLE tbl_table_group_game_action
    VALIDATE CONSTRAINT fk_tg_game_action_actor;
ALTER TABLE tbl_table_group_game_action
    VALIDATE CONSTRAINT fk_tg_game_action_target;

-- Recreate every check, rather than merely checking its name in pg_constraint.
-- A same-name draft constraint can otherwise look installed while permitting
-- states the Java game engine cannot represent.
ALTER TABLE tbl_table_group_game
    DROP CONSTRAINT IF EXISTS ck_tg_game_topic,
    DROP CONSTRAINT IF EXISTS ck_tg_game_mode,
    DROP CONSTRAINT IF EXISTS ck_tg_game_status,
    DROP CONSTRAINT IF EXISTS ck_tg_game_phase,
    DROP CONSTRAINT IF EXISTS ck_tg_game_round,
    DROP CONSTRAINT IF EXISTS ck_tg_game_revision,
    DROP CONSTRAINT IF EXISTS ck_tg_game_created_username,
    DROP CONSTRAINT IF EXISTS ck_tg_game_selected_username,
    DROP CONSTRAINT IF EXISTS ck_tg_game_status_phase,
    DROP CONSTRAINT IF EXISTS ck_tg_game_state_shape;

ALTER TABLE tbl_table_group_game
    ADD CONSTRAINT ck_tg_game_topic
        CHECK (topic = 'WHO_PAYS') NOT VALID,
    ADD CONSTRAINT ck_tg_game_mode
        CHECK (mode IN ('ROCK_PAPER_SCISSORS', 'DICE', 'VOTE')) NOT VALID,
    ADD CONSTRAINT ck_tg_game_status
        CHECK (status IN ('LOBBY', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')) NOT VALID,
    ADD CONSTRAINT ck_tg_game_phase
        CHECK (phase IN (
            'LOBBY', 'RPS', 'DICE', 'VOTE', 'VOTE_TIE_DICE', 'COMPLETED', 'CANCELLED'
        )) NOT VALID,
    ADD CONSTRAINT ck_tg_game_round CHECK (round_number >= 0) NOT VALID,
    ADD CONSTRAINT ck_tg_game_revision CHECK (revision >= 1) NOT VALID,
    ADD CONSTRAINT ck_tg_game_created_username
        -- Java/Dart count UTF-16 code units while PostgreSQL char_length counts
        -- code points, so keep username snapshots to nonblank + varchar(30).
        CHECK (btrim(created_by_username) <> '') NOT VALID,
    ADD CONSTRAINT ck_tg_game_selected_username
        CHECK (selected_username IS NULL OR btrim(selected_username) <> '') NOT VALID,
    ADD CONSTRAINT ck_tg_game_status_phase CHECK (
        (status = 'LOBBY' AND phase = 'LOBBY')
        OR (status = 'IN_PROGRESS' AND phase IN ('RPS', 'DICE', 'VOTE', 'VOTE_TIE_DICE'))
        OR (status = 'COMPLETED' AND phase = 'COMPLETED')
        OR (status = 'CANCELLED' AND phase = 'CANCELLED')
    ) NOT VALID,
    ADD CONSTRAINT ck_tg_game_state_shape CHECK (
        (
            status = 'LOBBY'
            AND round_number = 0
            AND join_deadline_at IS NOT NULL
            AND action_deadline_at IS NULL
            AND completed_at IS NULL
            AND selected_user_id IS NULL
            AND selected_username IS NULL
            AND outcome IS NULL
            AND result_message IS NULL
            AND cancellation_reason IS NULL
        )
        OR (
            status = 'IN_PROGRESS'
            AND round_number >= 1
            AND join_deadline_at IS NULL
            AND action_deadline_at IS NOT NULL
            AND completed_at IS NULL
            AND selected_user_id IS NULL
            AND selected_username IS NULL
            AND outcome IS NULL
            AND result_message IS NULL
            AND cancellation_reason IS NULL
        )
        OR (
            status = 'COMPLETED'
            AND round_number >= 1
            AND join_deadline_at IS NULL
            AND action_deadline_at IS NULL
            AND completed_at IS NOT NULL
            AND selected_user_id IS NOT NULL
            AND selected_username IS NOT NULL
            AND outcome IS NOT NULL
            AND outcome IN ('ASSIGNED', 'VOLUNTEER')
            AND result_message IS NOT NULL
            AND char_length(btrim(result_message)) BETWEEN 1 AND 500
            AND cancellation_reason IS NULL
        )
        OR (
            status = 'CANCELLED'
            AND join_deadline_at IS NULL
            AND action_deadline_at IS NULL
            AND completed_at IS NOT NULL
            AND selected_user_id IS NULL
            AND selected_username IS NULL
            AND outcome IS NULL
            AND result_message IS NULL
            AND cancellation_reason IS NOT NULL
            AND char_length(btrim(cancellation_reason)) BETWEEN 1 AND 64
        )
    ) NOT VALID;

ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT ck_tg_game_topic;
ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT ck_tg_game_mode;
ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT ck_tg_game_status;
ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT ck_tg_game_phase;
ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT ck_tg_game_round;
ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT ck_tg_game_revision;
ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT ck_tg_game_created_username;
ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT ck_tg_game_selected_username;
ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT ck_tg_game_status_phase;
ALTER TABLE tbl_table_group_game
    VALIDATE CONSTRAINT ck_tg_game_state_shape;

ALTER TABLE tbl_table_group_game_player
    DROP CONSTRAINT IF EXISTS ck_tg_game_player_username,
    DROP CONSTRAINT IF EXISTS ck_tg_game_player_status;
ALTER TABLE tbl_table_group_game_player
    ADD CONSTRAINT ck_tg_game_player_username
        CHECK (btrim(username) <> '') NOT VALID,
    ADD CONSTRAINT ck_tg_game_player_status
        CHECK (status IN ('ACTIVE', 'SAFE', 'TIMED_OUT', 'LEFT')) NOT VALID;
ALTER TABLE tbl_table_group_game_player
    VALIDATE CONSTRAINT ck_tg_game_player_username;
ALTER TABLE tbl_table_group_game_player
    VALIDATE CONSTRAINT ck_tg_game_player_status;

ALTER TABLE tbl_table_group_game_action
    DROP CONSTRAINT IF EXISTS ck_tg_game_action_round,
    DROP CONSTRAINT IF EXISTS ck_tg_game_action_phase,
    DROP CONSTRAINT IF EXISTS ck_tg_game_action_type,
    DROP CONSTRAINT IF EXISTS ck_tg_game_action_shape;
ALTER TABLE tbl_table_group_game_action
    ADD CONSTRAINT ck_tg_game_action_round
        CHECK (round_number >= 1) NOT VALID,
    ADD CONSTRAINT ck_tg_game_action_phase
        CHECK (phase IN ('RPS', 'DICE', 'VOTE', 'VOTE_TIE_DICE')) NOT VALID,
    ADD CONSTRAINT ck_tg_game_action_type
        CHECK (action IN ('ROCK', 'PAPER', 'SCISSORS', 'ROLL', 'VOTE', 'VOLUNTEER')) NOT VALID,
    ADD CONSTRAINT ck_tg_game_action_shape CHECK (
        -- Ordinary self-vote and VOLUNTEER are distinct product choices.
        (
            phase = 'RPS'
            AND action IN ('ROCK', 'PAPER', 'SCISSORS')
            AND target_user_id IS NULL
            AND value IS NULL
        )
        OR (
            phase IN ('DICE', 'VOTE_TIE_DICE')
            AND action = 'ROLL'
            AND target_user_id IS NULL
            AND value IS NOT NULL
            AND value BETWEEN 1 AND 6
        )
        OR (
            phase = 'VOTE'
            AND action = 'VOTE'
            AND target_user_id IS NOT NULL
            AND value IS NULL
        )
        OR (
            phase = 'VOTE'
            AND action = 'VOLUNTEER'
            AND target_user_id IS NOT NULL
            AND target_user_id = actor_user_id
            AND value IS NULL
        )
    ) NOT VALID;
ALTER TABLE tbl_table_group_game_action
    VALIDATE CONSTRAINT ck_tg_game_action_round;
ALTER TABLE tbl_table_group_game_action
    VALIDATE CONSTRAINT ck_tg_game_action_phase;
ALTER TABLE tbl_table_group_game_action
    VALIDATE CONSTRAINT ck_tg_game_action_type;
ALTER TABLE tbl_table_group_game_action
    VALIDATE CONSTRAINT ck_tg_game_action_shape;

DROP INDEX IF EXISTS uk_tg_game_one_active;
CREATE UNIQUE INDEX uk_tg_game_one_active
    ON tbl_table_group_game (table_group_id)
    WHERE status IN ('LOBBY', 'IN_PROGRESS');

DROP INDEX IF EXISTS idx_tg_game_due;
CREATE INDEX idx_tg_game_due
    ON tbl_table_group_game (status, join_deadline_at, action_deadline_at, id);

DROP INDEX IF EXISTS idx_tg_game_table_status;
CREATE INDEX idx_tg_game_table_status
    ON tbl_table_group_game (table_group_id, status);

DROP INDEX IF EXISTS idx_tg_game_player_status;
CREATE INDEX idx_tg_game_player_status
    ON tbl_table_group_game_player (game_id, status);

DROP INDEX IF EXISTS idx_tg_game_action_round;
CREATE INDEX idx_tg_game_action_round
    ON tbl_table_group_game_action (game_id, round_number);

DROP INDEX IF EXISTS idx_tg_game_action_revealed;
CREATE INDEX idx_tg_game_action_revealed
    ON tbl_table_group_game_action (game_id, revealed, round_number);

ALTER TABLE tbl_table_group_message
    DROP CONSTRAINT IF EXISTS fk_tg_message_game;
ALTER TABLE tbl_table_group_message
    ADD CONSTRAINT fk_tg_message_game
    FOREIGN KEY (game_id) REFERENCES tbl_table_group_game (id)
    ON DELETE CASCADE NOT VALID;
ALTER TABLE tbl_table_group_message
    VALIDATE CONSTRAINT fk_tg_message_game;

-- Hibernate can materialize the entity-level uniqueness as a table constraint
-- backed by an index with this same name. PostgreSQL will not drop an index
-- owned by a constraint, so reconcile both partial-upgrade shapes explicitly.
ALTER TABLE tbl_table_group_message
    DROP CONSTRAINT IF EXISTS uk_tg_message_game;
DROP INDEX IF EXISTS uk_tg_message_game;
CREATE UNIQUE INDEX uk_tg_message_game
    ON tbl_table_group_message (game_id)
    WHERE game_id IS NOT NULL;

-- Replace the hardening-era enum check with the server-owned GAME card type.
ALTER TABLE tbl_table_group_message
    DROP CONSTRAINT IF EXISTS ck_table_group_message_type;
ALTER TABLE tbl_table_group_message
    ADD CONSTRAINT ck_table_group_message_type
    CHECK (message_type IN ('TEXT', 'SYSTEM', 'IMAGE', 'GAME')) NOT VALID;

ALTER TABLE tbl_table_group_message
    DROP CONSTRAINT IF EXISTS ck_tg_message_game_shape;
ALTER TABLE tbl_table_group_message
    ADD CONSTRAINT ck_tg_message_game_shape
    CHECK (
        (message_type = 'GAME' AND game_id IS NOT NULL)
        OR (message_type <> 'GAME' AND game_id IS NULL)
    ) NOT VALID;

ALTER TABLE tbl_table_group_message
    VALIDATE CONSTRAINT ck_table_group_message_type;
ALTER TABLE tbl_table_group_message
    VALIDATE CONSTRAINT ck_tg_message_game_shape;

COMMIT;
