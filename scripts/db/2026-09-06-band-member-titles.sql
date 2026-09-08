-- Additive and rerunnable. Run before starting the title-enabled backend.
-- Preserves membership roles/statuses, events, invitations and existing titles.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE tbl_band_member
    ADD COLUMN IF NOT EXISTS member_title varchar(256),
    ADD COLUMN IF NOT EXISTS title_version bigint NOT NULL DEFAULT 0;

-- Also handle an earlier Hibernate-created nullable column without touching
-- an existing title or replacing a valid version.
UPDATE tbl_band_member SET title_version = 0 WHERE title_version IS NULL;
ALTER TABLE tbl_band_member
    ALTER COLUMN title_version SET DEFAULT 0,
    ALTER COLUMN title_version SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conrelid = 'tbl_band_member'::regclass
          AND conname = 'ck_band_member_title_version') THEN
        ALTER TABLE tbl_band_member ADD CONSTRAINT ck_band_member_title_version
            CHECK (title_version >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
        WHERE conrelid = 'tbl_band_member'::regclass
          AND conname = 'ck_band_member_title_storage_limit') THEN
        ALTER TABLE tbl_band_member ADD CONSTRAINT ck_band_member_title_storage_limit
            CHECK (member_title IS NULL OR char_length(member_title) <= 256);
    END IF;
END $$;

COMMENT ON COLUMN tbl_band_member.member_title IS
    'Optional per-band display title, never an authority role. API limit: 20 Unicode graphemes, raw limit: 256 code points.';
COMMENT ON COLUMN tbl_band_member.title_version IS
    'Optimistic title and membership-tenure revision. Must not reset on reinvitation.';

INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-06-band-member-titles') ON CONFLICT DO NOTHING;
COMMIT;
