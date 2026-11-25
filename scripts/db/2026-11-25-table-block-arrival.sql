-- A pending application's timestamp cannot establish when its seat was accepted.
BEGIN;
SET LOCAL lock_timeout = '10s';
ALTER TABLE tbl_table_group_participants ADD COLUMN IF NOT EXISTS accepted_at timestamptz;
COMMIT;
