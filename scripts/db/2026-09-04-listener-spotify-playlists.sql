-- Listener Spotify playlist showcase contract.
--
-- Run with psql -v ON_ERROR_STOP=1 while listener-profile writers are stopped.
-- This repository has no automatic production migration runner. The migration
-- is additive and rerunnable, but every abort must be reconciled before the
-- playlist-aware application binary starts.

BEGIN;

DO $migration$
BEGIN
    IF to_regclass('"tbl_listener-profile"') IS NULL THEN
        RAISE EXCEPTION 'Listener playlist migration requires "tbl_listener-profile"';
    END IF;
END
$migration$;

ALTER TABLE "tbl_listener-profile"
    ADD COLUMN IF NOT EXISTS playlist_revision bigint;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'tbl_listener-profile'
          AND column_name = 'playlist_revision'
          AND data_type = 'bigint'
    ) THEN
        RAISE EXCEPTION 'listener playlist revision must be bigint';
    END IF;
END
$migration$;

UPDATE "tbl_listener-profile"
SET playlist_revision = 0
WHERE playlist_revision IS NULL;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM "tbl_listener-profile" WHERE playlist_revision < 0
    ) THEN
        RAISE EXCEPTION 'Listener profile contains a negative playlist revision';
    END IF;
END
$migration$;

ALTER TABLE "tbl_listener-profile"
    ALTER COLUMN playlist_revision SET DEFAULT 0;
ALTER TABLE "tbl_listener-profile"
    ALTER COLUMN playlist_revision SET NOT NULL;
ALTER TABLE "tbl_listener-profile"
    DROP CONSTRAINT IF EXISTS ck_listener_profile_playlist_revision;
ALTER TABLE "tbl_listener-profile"
    ADD CONSTRAINT ck_listener_profile_playlist_revision
    CHECK (playlist_revision >= 0) NOT VALID;
ALTER TABLE "tbl_listener-profile"
    VALIDATE CONSTRAINT ck_listener_profile_playlist_revision;

CREATE TABLE IF NOT EXISTS tbl_listener_spotify_playlist (
    id uuid,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    listener_profile_id uuid,
    spotify_playlist_id varchar(64),
    title varchar(255),
    cover_image_url varchar(2048),
    spotify_url varchar(512),
    position integer
);

-- Reconcile a table that Hibernate may have created during local development,
-- or a rollout that stopped after only part of the additive DDL completed.
ALTER TABLE tbl_listener_spotify_playlist ADD COLUMN IF NOT EXISTS id uuid;
ALTER TABLE tbl_listener_spotify_playlist ADD COLUMN IF NOT EXISTS created_at timestamp without time zone;
ALTER TABLE tbl_listener_spotify_playlist ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone;
ALTER TABLE tbl_listener_spotify_playlist ADD COLUMN IF NOT EXISTS listener_profile_id uuid;
ALTER TABLE tbl_listener_spotify_playlist ADD COLUMN IF NOT EXISTS spotify_playlist_id varchar(64);
ALTER TABLE tbl_listener_spotify_playlist ADD COLUMN IF NOT EXISTS title varchar(255);
ALTER TABLE tbl_listener_spotify_playlist ADD COLUMN IF NOT EXISTS cover_image_url varchar(2048);
ALTER TABLE tbl_listener_spotify_playlist ADD COLUMN IF NOT EXISTS spotify_url varchar(512);
ALTER TABLE tbl_listener_spotify_playlist ADD COLUMN IF NOT EXISTS position integer;

DO $migration$
DECLARE
    incompatible_column text;
BEGIN
    SELECT expected.column_name
    INTO incompatible_column
    FROM (VALUES
        ('id', 'uuid', NULL::integer),
        ('created_at', 'timestamp without time zone', NULL::integer),
        ('updated_at', 'timestamp without time zone', NULL::integer),
        ('listener_profile_id', 'uuid', NULL::integer),
        ('spotify_playlist_id', 'character varying', 64),
        ('title', 'character varying', 255),
        ('cover_image_url', 'character varying', 2048),
        ('spotify_url', 'character varying', 512),
        ('position', 'integer', NULL::integer)
    ) AS expected(column_name, data_type, maximum_length)
    LEFT JOIN information_schema.columns actual
      ON actual.table_schema = current_schema()
     AND actual.table_name = 'tbl_listener_spotify_playlist'
     AND actual.column_name = expected.column_name
    WHERE actual.column_name IS NULL
       OR actual.data_type <> expected.data_type
       OR (expected.maximum_length IS NOT NULL
           AND actual.character_maximum_length <> expected.maximum_length)
    LIMIT 1;

    IF incompatible_column IS NOT NULL THEN
        RAISE EXCEPTION 'listener Spotify playlist column % has an incompatible type', incompatible_column;
    END IF;
END
$migration$;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tbl_listener_spotify_playlist playlist
        WHERE playlist.id IS NULL
           OR playlist.listener_profile_id IS NULL
           OR playlist.spotify_playlist_id IS NULL
           OR playlist.title IS NULL
           OR playlist.cover_image_url IS NULL
           OR playlist.spotify_url IS NULL
           OR playlist.position IS NULL
           OR playlist.spotify_playlist_id !~ '^[A-Za-z0-9]{22}$'
           OR btrim(playlist.title) = ''
           OR playlist.cover_image_url !~*
              '^https://([A-Za-z0-9-]+[.])*(scdn[.]co|spotifycdn[.]com)(:443)?/[^[:space:]#]*$'
           OR playlist.spotify_url <>
              'https://open.spotify.com/playlist/' || playlist.spotify_playlist_id
           OR playlist.position NOT BETWEEN 0 AND 3
           OR NOT EXISTS (
                SELECT 1
                FROM "tbl_listener-profile" listener
                WHERE listener.id = playlist.listener_profile_id
           )
    ) THEN
        RAISE EXCEPTION 'Listener Spotify playlist data violates the canonical contract';
    END IF;

    IF EXISTS (
        SELECT id
        FROM tbl_listener_spotify_playlist
        GROUP BY id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Listener Spotify playlist ids are not unique';
    END IF;

    IF EXISTS (
        SELECT listener_profile_id, position
        FROM tbl_listener_spotify_playlist
        GROUP BY listener_profile_id, position
        HAVING count(*) > 1
    ) OR EXISTS (
        SELECT listener_profile_id, spotify_playlist_id
        FROM tbl_listener_spotify_playlist
        GROUP BY listener_profile_id, spotify_playlist_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Listener Spotify playlist positions or Spotify ids are duplicated';
    END IF;

    IF EXISTS (
        SELECT listener_profile_id
        FROM tbl_listener_spotify_playlist
        GROUP BY listener_profile_id
        HAVING min(position) <> 0
            OR max(position) <> count(*) - 1
    ) THEN
        RAISE EXCEPTION 'Listener Spotify playlist positions must be contiguous from zero';
    END IF;
END
$migration$;

ALTER TABLE tbl_listener_spotify_playlist ALTER COLUMN id SET NOT NULL;
ALTER TABLE tbl_listener_spotify_playlist ALTER COLUMN listener_profile_id SET NOT NULL;
ALTER TABLE tbl_listener_spotify_playlist ALTER COLUMN spotify_playlist_id SET NOT NULL;
ALTER TABLE tbl_listener_spotify_playlist ALTER COLUMN title SET NOT NULL;
ALTER TABLE tbl_listener_spotify_playlist ALTER COLUMN cover_image_url SET NOT NULL;
ALTER TABLE tbl_listener_spotify_playlist ALTER COLUMN spotify_url SET NOT NULL;
ALTER TABLE tbl_listener_spotify_playlist ALTER COLUMN position SET NOT NULL;

DO $migration$
DECLARE
    primary_key_definition text;
BEGIN
    SELECT pg_get_constraintdef(constraint_row.oid)
    INTO primary_key_definition
    FROM pg_constraint constraint_row
    WHERE constraint_row.conrelid = 'tbl_listener_spotify_playlist'::regclass
      AND constraint_row.contype = 'p';

    IF primary_key_definition IS NULL THEN
        ALTER TABLE tbl_listener_spotify_playlist
            ADD CONSTRAINT pk_listener_spotify_playlist PRIMARY KEY (id);
    ELSIF primary_key_definition <> 'PRIMARY KEY (id)' THEN
        RAISE EXCEPTION 'Listener Spotify playlist primary key must contain only id';
    END IF;
END
$migration$;

-- Hibernate/local partial rollouts may have installed the same relationship
-- under a generated name and with NO ACTION. Drop every FK pairing this child
-- column with the listener PK before installing the one canonical CASCADE FK.
DO $migration$
DECLARE
    legacy_fk record;
BEGIN
    FOR legacy_fk IN
        SELECT DISTINCT constraint_row.conname
        FROM pg_constraint constraint_row
        JOIN unnest(constraint_row.conkey) WITH ORDINALITY
             AS child_key(attnum, ordinal_position) ON TRUE
        JOIN unnest(constraint_row.confkey) WITH ORDINALITY
             AS parent_key(attnum, ordinal_position)
          ON parent_key.ordinal_position = child_key.ordinal_position
        JOIN pg_attribute child_column
          ON child_column.attrelid = constraint_row.conrelid
         AND child_column.attnum = child_key.attnum
        JOIN pg_attribute parent_column
          ON parent_column.attrelid = constraint_row.confrelid
         AND parent_column.attnum = parent_key.attnum
        WHERE constraint_row.contype = 'f'
          AND constraint_row.conrelid = 'tbl_listener_spotify_playlist'::regclass
          AND constraint_row.confrelid = '"tbl_listener-profile"'::regclass
          AND child_column.attname = 'listener_profile_id'
          AND parent_column.attname = 'id'
    LOOP
        EXECUTE format(
            'ALTER TABLE tbl_listener_spotify_playlist DROP CONSTRAINT %I',
            legacy_fk.conname
        );
    END LOOP;
END
$migration$;

ALTER TABLE tbl_listener_spotify_playlist
    ADD CONSTRAINT fk_listener_spotify_playlist_profile
    FOREIGN KEY (listener_profile_id)
    REFERENCES "tbl_listener-profile" (id)
    ON DELETE CASCADE
    NOT VALID;

ALTER TABLE tbl_listener_spotify_playlist
    DROP CONSTRAINT IF EXISTS uk_listener_spotify_playlist_position;
ALTER TABLE tbl_listener_spotify_playlist
    ADD CONSTRAINT uk_listener_spotify_playlist_position
    UNIQUE (listener_profile_id, position);

ALTER TABLE tbl_listener_spotify_playlist
    DROP CONSTRAINT IF EXISTS uk_listener_spotify_playlist_spotify_id;
ALTER TABLE tbl_listener_spotify_playlist
    ADD CONSTRAINT uk_listener_spotify_playlist_spotify_id
    UNIQUE (listener_profile_id, spotify_playlist_id);

ALTER TABLE tbl_listener_spotify_playlist
    DROP CONSTRAINT IF EXISTS ck_listener_spotify_playlist_position;
ALTER TABLE tbl_listener_spotify_playlist
    ADD CONSTRAINT ck_listener_spotify_playlist_position
    CHECK (position BETWEEN 0 AND 3) NOT VALID;

ALTER TABLE tbl_listener_spotify_playlist
    DROP CONSTRAINT IF EXISTS ck_listener_spotify_playlist_id;
ALTER TABLE tbl_listener_spotify_playlist
    ADD CONSTRAINT ck_listener_spotify_playlist_id
    CHECK (spotify_playlist_id ~ '^[A-Za-z0-9]{22}$') NOT VALID;

ALTER TABLE tbl_listener_spotify_playlist
    DROP CONSTRAINT IF EXISTS ck_listener_spotify_playlist_title;
ALTER TABLE tbl_listener_spotify_playlist
    ADD CONSTRAINT ck_listener_spotify_playlist_title
    CHECK (btrim(title) <> '') NOT VALID;

ALTER TABLE tbl_listener_spotify_playlist
    DROP CONSTRAINT IF EXISTS ck_listener_spotify_playlist_cover_url;
ALTER TABLE tbl_listener_spotify_playlist
    ADD CONSTRAINT ck_listener_spotify_playlist_cover_url
    CHECK (cover_image_url ~*
           '^https://([A-Za-z0-9-]+[.])*(scdn[.]co|spotifycdn[.]com)(:443)?/[^[:space:]#]*$') NOT VALID;

ALTER TABLE tbl_listener_spotify_playlist
    DROP CONSTRAINT IF EXISTS ck_listener_spotify_playlist_canonical_url;
ALTER TABLE tbl_listener_spotify_playlist
    ADD CONSTRAINT ck_listener_spotify_playlist_canonical_url
    CHECK (spotify_url = 'https://open.spotify.com/playlist/' || spotify_playlist_id) NOT VALID;

ALTER TABLE tbl_listener_spotify_playlist
    VALIDATE CONSTRAINT fk_listener_spotify_playlist_profile;
ALTER TABLE tbl_listener_spotify_playlist
    VALIDATE CONSTRAINT ck_listener_spotify_playlist_position;
ALTER TABLE tbl_listener_spotify_playlist
    VALIDATE CONSTRAINT ck_listener_spotify_playlist_id;
ALTER TABLE tbl_listener_spotify_playlist
    VALIDATE CONSTRAINT ck_listener_spotify_playlist_title;
ALTER TABLE tbl_listener_spotify_playlist
    VALIDATE CONSTRAINT ck_listener_spotify_playlist_cover_url;
ALTER TABLE tbl_listener_spotify_playlist
    VALIDATE CONSTRAINT ck_listener_spotify_playlist_canonical_url;

COMMENT ON COLUMN "tbl_listener-profile".playlist_revision IS
    'Scalar child-aggregate revision used to advance the listener optimistic version on playlist replacement.';
COMMENT ON TABLE tbl_listener_spotify_playlist IS
    'At most four ordered, server-hydrated public Spotify playlist snapshots per listener profile.';

COMMIT;
