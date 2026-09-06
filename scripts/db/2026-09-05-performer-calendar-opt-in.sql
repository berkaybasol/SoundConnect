-- Run after 2026-09-05-musician-calendar.sql. This forward migration preserves
-- events, connections and all explicit calendar choices. Safe to re-run.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE tbl_musician_calendar_settings ALTER COLUMN visible SET DEFAULT false;
-- Version zero is the former untouched implicit default, not a user's choice.
-- Advance its version so already-open clients cannot overwrite this correction.
UPDATE tbl_musician_calendar_settings SET visible = false, version = 1
WHERE visible = true AND version = 0;

CREATE TABLE IF NOT EXISTS tbl_band_calendar_settings (
    band_id uuid PRIMARY KEY,
    visible boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_band_calendar_version CHECK (version >= 0),
    CONSTRAINT fk_band_calendar_band FOREIGN KEY (band_id) REFERENCES tbl_band(id) ON DELETE CASCADE
);
ALTER TABLE tbl_band_calendar_settings
    ALTER COLUMN visible SET DEFAULT false,
    ALTER COLUMN visible SET NOT NULL,
    ALTER COLUMN version SET DEFAULT 0,
    ALTER COLUMN version SET NOT NULL,
    DROP CONSTRAINT IF EXISTS ck_band_calendar_version;
ALTER TABLE tbl_band_calendar_settings ADD CONSTRAINT ck_band_calendar_version CHECK (version >= 0);
DO $$
DECLARE existing_fk record;
BEGIN
    FOR existing_fk IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'tbl_band_calendar_settings'::regclass AND contype = 'f'
        AND confrelid = 'tbl_band'::regclass
    LOOP
        EXECUTE format('ALTER TABLE tbl_band_calendar_settings DROP CONSTRAINT %I', existing_fk.conname);
    END LOOP;
    ALTER TABLE tbl_band_calendar_settings ADD CONSTRAINT fk_band_calendar_band
        FOREIGN KEY (band_id) REFERENCES tbl_band(id) ON DELETE CASCADE;
END $$;
INSERT INTO tbl_band_calendar_settings (band_id) SELECT id FROM tbl_band
ON CONFLICT (band_id) DO NOTHING;
COMMIT;
