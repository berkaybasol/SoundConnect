-- SoundConnect TableGroup global active-feed index rollout.
--
-- Prerequisite: scripts/db/2026-08-17-tablegroup-hardening.sql
-- Run with: psql -v ON_ERROR_STOP=1 -f scripts/db/2026-08-31-tablegroup-global-feed.sql
-- The migration is transactional and rerunnable. Hardened start_at is an exact
-- instant and is the only safe source for a missing legacy created_at. Its UTC
-- wall-clock representation matches BaseEntity/JPA auditing; the migration
-- refuses rows without that source instead of inventing an ordering timestamp.
-- The leading status and deterministic feed-order columns support the
-- cross-city active feed; expiry is included so PostgreSQL can evaluate the
-- live-row predicate directly from the index when an index-only plan is
-- available.

BEGIN;

DO $table_group_global_feed_prerequisite$
BEGIN
    IF to_regclass('tbl_table_group') IS NULL THEN
        RAISE EXCEPTION 'table-group global-feed rollout requires tbl_table_group'
            USING ERRCODE = '42P01';
    END IF;

    IF EXISTS (
        SELECT required.column_name
          FROM (VALUES ('status'), ('created_at'), ('id'), ('expires_at'), ('start_at'))
               AS required(column_name)
         WHERE NOT EXISTS (
             SELECT 1
               FROM information_schema.columns existing
              WHERE existing.table_schema = current_schema()
                AND existing.table_name = 'tbl_table_group'
                AND existing.column_name = required.column_name
         )
    ) THEN
        RAISE EXCEPTION 'table-group global-feed rollout requires status, created_at, id, expires_at, and start_at columns'
            USING ERRCODE = '42703';
    END IF;
END
$table_group_global_feed_prerequisite$;

DO $table_group_global_feed_timestamp_contract$
DECLARE
    created_type text;
    start_type text;
BEGIN
    SELECT data_type INTO created_type
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'tbl_table_group'
       AND column_name = 'created_at';
    SELECT data_type INTO start_type
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'tbl_table_group'
       AND column_name = 'start_at';

    IF created_type <> 'timestamp without time zone'
            OR start_type <> 'timestamp with time zone' THEN
        RAISE EXCEPTION 'table-group global-feed rollout requires UTC wall-clock created_at and hardened timestamptz start_at'
            USING ERRCODE = '42804';
    END IF;

    IF EXISTS (
        SELECT 1 FROM tbl_table_group
         WHERE created_at IS NULL AND start_at IS NULL
    ) THEN
        RAISE EXCEPTION 'table-group global-feed rollout blocked: rows without created_at/start_at require manual reconciliation'
            USING ERRCODE = '23514';
    END IF;
END
$table_group_global_feed_timestamp_contract$;

-- start_at was normalized to an exact UTC instant by the hardening migration.
-- Convert only its representation; never overwrite an existing audit value.
UPDATE tbl_table_group
   SET created_at = start_at AT TIME ZONE 'UTC'
 WHERE created_at IS NULL;

ALTER TABLE tbl_table_group
    ALTER COLUMN created_at SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_group_status_created_id_active_feed
    ON tbl_table_group (status, created_at DESC, id DESC)
    INCLUDE (expires_at);

COMMIT;
