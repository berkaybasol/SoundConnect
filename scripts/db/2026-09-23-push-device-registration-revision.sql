-- Apply after push-delivery-foundation and before enabling a revision-aware backend.
-- Installation-scoped monotonic client revisions fence delayed register/logout requests.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
ALTER TABLE tbl_push_device ADD COLUMN IF NOT EXISTS client_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE tbl_push_device ADD COLUMN IF NOT EXISTS presentation_version varchar(32);
ALTER TABLE tbl_push_device DROP CONSTRAINT IF EXISTS ck_push_device_client_revision;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_client_revision
    CHECK (client_revision BETWEEN 0 AND 9007199254740991);
ALTER TABLE tbl_push_device DROP CONSTRAINT IF EXISTS ck_push_device_presentation;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_presentation
    CHECK (presentation_version IS NULL OR (platform='ANDROID' AND presentation_version='ANDROID_DM_V1'));
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-23-push-device-registration-revision') ON CONFLICT DO NOTHING;
COMMIT;
