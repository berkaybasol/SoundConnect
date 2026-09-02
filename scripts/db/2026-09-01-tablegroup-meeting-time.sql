-- Separates the user-visible meeting time from the technical table lifetime.
--
-- Legacy clients stored the selected time in expires_at. Preserve that value
-- as meeting_at, but do not rewrite expires_at during this compatibility
-- rollout. The application rollout will use expires_at exclusively as the
-- automatic-close boundary (created/start time + 24 hours).
-- Run with psql -v ON_ERROR_STOP=1 after tablegroup-optional-venue.

BEGIN;

DO $table_group_meeting_time_preflight$
DECLARE
    start_type text;
    expires_type text;
    meeting_type text;
BEGIN
    IF to_regclass('tbl_table_group') IS NULL THEN
        RAISE EXCEPTION 'table-group meeting-time rollout requires tbl_table_group';
    END IF;

    SELECT columns.data_type
      INTO start_type
      FROM information_schema.columns columns
     WHERE columns.table_schema = current_schema()
       AND columns.table_name = 'tbl_table_group'
       AND columns.column_name = 'start_at';

    SELECT columns.data_type
      INTO expires_type
      FROM information_schema.columns columns
     WHERE columns.table_schema = current_schema()
       AND columns.table_name = 'tbl_table_group'
       AND columns.column_name = 'expires_at';

    SELECT columns.data_type
      INTO meeting_type
      FROM information_schema.columns columns
     WHERE columns.table_schema = current_schema()
       AND columns.table_name = 'tbl_table_group'
       AND columns.column_name = 'meeting_at';

    IF start_type IS NULL OR expires_type IS NULL THEN
        RAISE EXCEPTION
            'table-group meeting-time rollout requires start_at and expires_at columns';
    END IF;

    IF start_type <> 'timestamp with time zone'
       OR expires_type <> 'timestamp with time zone' THEN
        RAISE EXCEPTION
            'table-group meeting-time rollout requires timestamptz start_at/expires_at, found %/%',
            start_type,
            expires_type;
    END IF;

    IF meeting_type IS NOT NULL
       AND meeting_type <> 'timestamp with time zone' THEN
        RAISE EXCEPTION
            'table-group meeting-time rollout requires meeting_at to be timestamptz, found %',
            meeting_type;
    END IF;
END
$table_group_meeting_time_preflight$;

ALTER TABLE tbl_table_group
    ADD COLUMN IF NOT EXISTS meeting_at timestamp with time zone;

-- A failed/partial prerelease attempt may have tightened this too early.
-- Restore the rolling-deployment contract deterministically on every run.
ALTER TABLE tbl_table_group
    ALTER COLUMN meeting_at DROP NOT NULL;

-- expires_at was the released client's selected visible time. Backfill only
-- missing values so a rerun never overwrites data written by the new client.
UPDATE tbl_table_group
   SET meeting_at = expires_at
 WHERE meeting_at IS NULL;

DO $table_group_meeting_time_bounds$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group
         WHERE meeting_at IS NOT NULL
           AND (
               meeting_at <= start_at
               OR meeting_at > expires_at
               OR meeting_at > start_at + INTERVAL '24 hours'
           )
    ) THEN
        RAISE EXCEPTION
            'table-group meeting-time rollout found values outside the table lifetime; manual reconciliation is required'
            USING ERRCODE = '23514';
    END IF;
END
$table_group_meeting_time_bounds$;

-- Keep the column nullable during the rolling deployment so an old API
-- process cannot fail an insert after the schema is migrated. New API writes
-- require meetingAt, and a later cleanup migration may tighten nullability
-- after every old process and client contract has been retired.
ALTER TABLE tbl_table_group
    DROP CONSTRAINT IF EXISTS ck_table_group_meeting_time;

ALTER TABLE tbl_table_group
    ADD CONSTRAINT ck_table_group_meeting_time
    CHECK (
        meeting_at IS NULL
        OR (
            meeting_at > start_at
            AND meeting_at <= expires_at
            AND meeting_at <= start_at + INTERVAL '24 hours'
        )
    ) NOT VALID;

ALTER TABLE tbl_table_group
    VALIDATE CONSTRAINT ck_table_group_meeting_time;

COMMENT ON COLUMN tbl_table_group.meeting_at IS
    'User-visible meeting instant; nullable only for rolling-deployment compatibility. expires_at is the technical automatic-close instant.';

COMMIT;
