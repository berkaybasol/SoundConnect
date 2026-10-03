-- Apply after push-device-registration-revision, before starting the V2-capable backend.
-- This changes capability validation only; it does not enable any notification type.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
ALTER TABLE tbl_push_device DROP CONSTRAINT IF EXISTS ck_push_device_presentation;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_presentation
    CHECK (presentation_version IS NULL OR (platform='ANDROID'
        AND presentation_version IN ('ANDROID_DM_V1','ANDROID_NATIVE_V2')));
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-24-push-native-venue-capability') ON CONFLICT DO NOTHING;
COMMIT;
