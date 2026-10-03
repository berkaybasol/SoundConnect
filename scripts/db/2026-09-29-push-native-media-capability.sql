-- Explicit forward migration: V6 adds MEDIA native while retaining DM, venue and application support.
-- No device is upgraded automatically and no notification rollout/allowlist is changed.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
DO $$ BEGIN
    IF (SELECT count(*) FROM soundconnect_schema_migrations WHERE migration_id IN (
        '2026-09-22-push-delivery-foundation','2026-09-23-push-device-registration-revision',
        '2026-09-24-push-native-venue-capability','2026-09-24-venue-application-notifications',
        '2026-09-24-push-native-studio-capability','2026-09-28-push-native-follow-capability')) <> 6 THEN
        RAISE EXCEPTION 'Media capability requires the complete V5 migration chain';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass
        AND conname='ck_push_device_presentation' AND contype='c' AND convalidated
        AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V5%')
        OR NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='tbl_push_device'::regclass
        AND conname='ck_push_device_application_scope' AND contype='c' AND convalidated
        AND pg_get_constraintdef(oid) LIKE '%presentation_version IS NOT NULL%'
        AND pg_get_constraintdef(oid) LIKE '%ANDROID_NATIVE_V5%') THEN
        RAISE EXCEPTION 'Media capability requires validated V5 presentation and scope constraints';
    END IF;
END $$;
ALTER TABLE tbl_push_device DROP CONSTRAINT ck_push_device_presentation;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_presentation
    CHECK (presentation_version IS NULL OR (platform='ANDROID'
        AND presentation_version IN ('ANDROID_DM_V1','ANDROID_NATIVE_V2','ANDROID_NATIVE_V3','ANDROID_NATIVE_V4','ANDROID_NATIVE_V5','ANDROID_NATIVE_V6')));
ALTER TABLE tbl_push_device DROP CONSTRAINT IF EXISTS ck_push_device_application_scope;
ALTER TABLE tbl_push_device ADD CONSTRAINT ck_push_device_application_scope
    CHECK (application_scope_id IS NULL OR (platform='ANDROID'
        AND (revoked_at IS NOT NULL OR (presentation_version IS NOT NULL
            AND presentation_version IN ('ANDROID_NATIVE_V3','ANDROID_NATIVE_V4','ANDROID_NATIVE_V5','ANDROID_NATIVE_V6')))));
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-29-push-native-media-capability') ON CONFLICT DO NOTHING;
COMMIT;