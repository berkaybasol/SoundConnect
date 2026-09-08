-- Apply before deploying the invitation identity API. Additive and rerunnable.
-- Existing pending invitations get an identity; historical notifications stay
-- unversioned and cannot decide them. No membership status/consent is changed.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE tbl_band_member ADD COLUMN IF NOT EXISTS invitation_id uuid;
UPDATE tbl_band_member
SET invitation_id = gen_random_uuid()
WHERE status = 'PENDING' AND invitation_id IS NULL;
COMMENT ON COLUMN tbl_band_member.invitation_id IS
    'Immutable invitation identity; a fresh UUID is required for every invitation/reinvitation. Never inferred from historical notifications.';
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-07-band-invitation-identity') ON CONFLICT DO NOTHING;
COMMIT;
