-- Explicit forward migration: V4 adds studio native while retaining DM, venue and application support.
-- No device is upgraded automatically and no notification rollout/allowlist is changed.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
ALTER TABLE tbl_push_device DROP CONSTRAINT IF EXISTS ck_push_device_presentation;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_presentation
    CHECK (presentation_version IS NULL OR (platform='ANDROID'
        AND presentation_version IN ('ANDROID_DM_V1','ANDROID_NATIVE_V2','ANDROID_NATIVE_V3','ANDROID_NATIVE_V4')));
ALTER TABLE tbl_push_device DROP CONSTRAINT IF EXISTS ck_push_device_application_scope;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_application_scope
    CHECK (application_scope_id IS NULL OR (platform='ANDROID'
        AND (revoked_at IS NOT NULL OR (presentation_version IS NOT NULL
            AND presentation_version IN ('ANDROID_NATIVE_V3','ANDROID_NATIVE_V4')))));
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-24-push-native-studio-capability') ON CONFLICT DO NOTHING;
COMMIT;